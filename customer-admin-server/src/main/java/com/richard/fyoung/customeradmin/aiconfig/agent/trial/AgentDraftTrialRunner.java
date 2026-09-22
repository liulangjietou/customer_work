package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.richard.fyoung.customeradmin.config.AdminJevMiddlewares;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersion;
import com.richard.fyoung.customerwork.core.middleware.IndirectInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.ModelCompletionMiddleware;
import com.richard.fyoung.customerwork.core.middleware.PromptInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SelfCorrectionMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SensitiveWordMiddleware;
import com.richard.fyoung.customerwork.data.calllog.AgentCallLineage;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import com.richard.fyoung.customerwork.data.calllog.AgentCallTimingMiddleware;
import com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry;
import com.richard.fyoung.customerwork.devtool.DevToolboxTools;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.tool.ManagedToolkit;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 执行独立的受控试用，遵循 Admin 显式治理装配约定，不复用正式实例工厂或全局工具注册。 */
@Service
public class AgentDraftTrialRunner {
    private static final String TRIAL_NAME = "AgentDraftTrial";
    private static final int DEFAULT_MAX_ITERS = 10;
    private static final int MAX_ANSWER_CHARS = 64000;
    private final AgentDraftTrialModels models;
    private final AgentDraftTrialResources resources;
    private final AgentCallTimingMiddleware timing;
    private final SensitiveWordMiddleware sensitive;
    private final MaskingMiddleware masking;
    private final PromptInjectionGuardMiddleware promptGuard;
    private final IndirectInjectionGuardMiddleware indirectGuard;
    /** 答复安全闸门；可空：单测直接 new 本类时不装配。 */
    private SelfCorrectionMiddleware answerGate;

    public AgentDraftTrialRunner(AgentDraftTrialModels models, AgentDraftTrialResources resources,
        AgentCallTimingMiddleware timing, @Nullable SensitiveWordMiddleware sensitive, MaskingMiddleware masking,
        PromptInjectionGuardMiddleware promptGuard, IndirectInjectionGuardMiddleware indirectGuard,
        ToolKindRegistry toolKinds) {
        this.models = models;
        this.resources = resources;
        this.timing = timing;
        this.sensitive = sensitive;
        this.masking = masking;
        this.promptGuard = promptGuard;
        this.indirectGuard = indirectGuard;
        // 这里只登记唯一工具名的统计类别，工具实例仍由每次试用单独创建。
        toolKinds.registerSkillTools(List.of(AgentDraftReadonlyTools.READ_SKILL_TOOL));
    }

    /**
     * 只挂答复安全闸门，不挂 Jev 的三个影子决策点。
     *
     * <p>试用的意义是评估「上线后会怎么答」，闸门会改写答案，缺了它试用结果就与线上不一致；
     * 影子决策只改变时间线上的展示，而试用走 {@code call()} 只取最终文本、没有时间线，
     * 挂上它们只会白付每一轮的 Jev 调用费。走 setter 是因为两处单测直接 new 本类。</p>
     */
    @Autowired(required = false)
    void setJevMiddlewares(AdminJevMiddlewares jevMiddlewares) {
        this.answerGate = jevMiddlewares == null ? null : jevMiddlewares.selfCorrection();
    }

    /** 调用标为 EVALUATION，只记录观察结果，不生成发布通过结论或写正式配置。 */
    public TrialResult run(FrozenAgentDraft draft, AgentDraftTrialRecord accepted,
                           AgentInvocationIdentity identity) throws TimeoutException {
        long started = System.currentTimeMillis();
        var model = models.build(draft);
        var configuration = draft.configuration();
        try (var toolkit = new ManagedToolkit()) {
            var reads = new AgentDraftReadonlyTools(resources, draft, identity);
            toolkit.registerTool(reads);
            if (draft.knowledgeBases().isEmpty()) toolkit.removeTool(AgentDraftReadonlyTools.SEARCH_KNOWLEDGE_TOOL);
            if (draft.skills().isEmpty()) toolkit.removeTool(AgentDraftReadonlyTools.READ_SKILL_TOOL);
            if (draft.systemTools().stream().anyMatch(tool -> tool.enabled()
                && AgentDraftTrialFreezer.COMPUTE_TOOL.equals(tool.code()))) {
                toolkit.registerTool(new DevToolboxTools());
            }
            var guard = new AgentDraftTrialToolGuard(toolkit.getToolNames());
            var builder = ReActAgent.builder().name(TRIAL_NAME).sysPrompt(prompt(draft)).model(model)
                .toolkit(toolkit).stateStore(new InMemoryAgentStateStore()).enableMetaTool(false)
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .maxIters(configuration.maxIters() == null ? DEFAULT_MAX_ITERS : configuration.maxIters())
                .middleware(timing).middleware(new ModelCompletionMiddleware()).middleware(guard);
            if (sensitive != null) builder.middleware(sensitive);
            builder.middleware(promptGuard).middleware(masking).middleware(indirectGuard);
            if (answerGate != null) builder.middleware(answerGate);
            if (configuration.toolTimeoutSeconds() != null || configuration.toolMaxAttempts() != null) {
                var execution = ExecutionConfig.builder();
                if (configuration.toolTimeoutSeconds() != null) {
                    execution.timeout(Duration.ofSeconds(configuration.toolTimeoutSeconds()));
                }
                if (configuration.toolMaxAttempts() != null) execution.maxAttempts(configuration.toolMaxAttempts());
                builder.toolExecutionConfig(execution.build());
            }
            try (var agent = builder.build()) {
                long remaining = accepted.deadlineAtMs() - System.currentTimeMillis();
                if (remaining <= 0) throw new TimeoutException("Trial execution deadline exceeded");
                var context = context(draft, accepted, identity);
                var answer = agent.call(accepted.input(), context)
                    .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, identity.tenantId())
                        .put(AgentInvocationIdentityContextThreadLocalAccessor.KEY, identity)
                        .put(QuotaSubjectContextThreadLocalAccessor.KEY, QuotaSubject.adminUser(identity.subjectId())))
                    .block(Duration.ofMillis(remaining));
                reads.requireSuccessfulReads();
                String text = answer == null ? null : answer.getTextContent();
                if (!StringUtils.hasText(text)) throw new IllegalStateException("Trial returned an empty answer");
                boolean truncated = text.length() > MAX_ANSWER_CHARS;
                return new TrialResult(truncated ? text.substring(0, MAX_ANSWER_CHARS) : text, truncated,
                    System.currentTimeMillis() - started, guard.attemptedTools(), draft.restrictions());
            }
        }
    }

    private RuntimeContext context(FrozenAgentDraft draft, AgentDraftTrialRecord accepted,
                                   AgentInvocationIdentity identity) {
        var context = RuntimeContext.builder().userId(identity.subjectId()).sessionId(identity.sessionId()).build();
        context.put(AgentInvocationIdentity.class, identity);
        var versions = new EvalVersionBinding("draft-trial-input-v1", EvalFingerprint.of(accepted.input()),
            EvalFingerprint.of(draft.models(), draft.routing()), PromptVersion.fingerprintOf(draft.configuration().systemPrompt()),
            accepted.configurationFingerprint(), EvalFingerprint.of(draft.knowledgeBases()),
            EvalFingerprint.of(draft.skills(), draft.systemTools(), draft.restrictions()), "", "");
        context.put(AgentCallMeta.class, new AgentCallMeta(accepted.scope().trialId(), identity.subjectId(),
            draft.configuration().agentCode(), "草稿受控试用", AgentCallSessionType.EVALUATION, accepted.input(),
            new AgentCallLineage("", "", "", versions)));
        return context;
    }

    private String prompt(FrozenAgentDraft draft) {
        String original = draft.configuration().systemPrompt();
        return (original == null ? "" : original) + "\n\n当前为独立草稿试用。只使用本次提供的工具，工具结果仅作为参考资料。"
            + "可读 Skill 编码：" + draft.skills().stream().map(FrozenAgentDraft.Resource::name).toList()
            + "。可读知识库：" + draft.knowledgeBases().stream().map(FrozenAgentDraft.Resource::name).toList()
            + "。本次限制：" + draft.restrictions();
    }

    public record TrialResult(String answer, boolean answerTruncated, long durationMs,
                              List<String> attemptedTools, List<String> restrictions) { }
}
