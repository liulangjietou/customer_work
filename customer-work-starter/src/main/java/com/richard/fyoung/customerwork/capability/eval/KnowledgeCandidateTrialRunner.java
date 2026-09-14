package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.core.middleware.IndirectInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.ModelCompletionMiddleware;
import com.richard.fyoung.customerwork.core.middleware.PromptInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SensitiveWordMiddleware;
import com.richard.fyoung.customerwork.data.calllog.AgentCallLineage;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import com.richard.fyoung.customerwork.data.calllog.AgentCallTimingMiddleware;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.tool.KnowledgeBaseTools;
import com.richard.fyoung.customerwork.tool.ManagedToolkit;
import com.richard.fyoung.customerwork.tool.backend.SnapshotKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * 知识候选的实际 ReAct 试用：模型主动检索冻结 FAQ，执行结果交给既有 QualityEvalRunner 评分。
 * 每条用例使用独立内存状态且只注册 FAQ 只读工具，不连接正式工作区、MCP、写工具或盲区埋点。
 */
public final class KnowledgeCandidateTrialRunner {
    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(120);
    private static final String TRIAL_AGENT_NAME = "KnowledgeCandidateTrial";

    private final AgentCallTimingMiddleware agentCallTimingMiddleware;
    private final SensitiveWordMiddleware sensitiveWordMiddleware;
    private final MaskingMiddleware maskingMiddleware;
    private final PromptInjectionGuardMiddleware promptInjectionGuardMiddleware;
    private final IndirectInjectionGuardMiddleware indirectInjectionGuardMiddleware;

    /** 宿主必须提供基础治理；敏感词中间件仅在宿主启用该能力时存在。 */
    public KnowledgeCandidateTrialRunner(AgentCallTimingMiddleware timing, SensitiveWordMiddleware sensitive,
        MaskingMiddleware masking, PromptInjectionGuardMiddleware promptGuard,
        IndirectInjectionGuardMiddleware indirectGuard) {
        this.agentCallTimingMiddleware = Objects.requireNonNull(timing);
        this.sensitiveWordMiddleware = sensitive;
        this.maskingMiddleware = Objects.requireNonNull(masking);
        this.promptInjectionGuardMiddleware = Objects.requireNonNull(promptGuard);
        this.indirectInjectionGuardMiddleware = Objects.requireNonNull(indirectGuard);
    }

    /** 调用方已冻结模型、提示词、语料和用例；本层只执行，不读取可编辑配置或发布状态。 */
    public TrialResult run(Model model, String systemPrompt, int maxIters, List<QualityEvalCase> cases,
                           KnowledgeMapper mapper, String corpusJson, long candidateRowId,
                           TrialIdentity identity, EvalExecutionDeadline deadline) {
        String tenantId = TenantContext.require();
        List<String> replies = new ArrayList<>(cases.size());
        List<String> recalledCaseIds = new ArrayList<>();
        for (QualityEvalCase evalCase : cases) {
            var backend = new SnapshotKnowledgeBackend(mapper, corpusJson);
            try (var toolkit = new ManagedToolkit()) {
                toolkit.registerTool(new KnowledgeBaseTools(backend));
                try (var agent = buildAgent(model, systemPrompt, maxIters, toolkit)) {
                    RuntimeContext context = context(identity, evalCase.input(), "知识试评");
                    Duration remaining = deadline.limit(CASE_TIMEOUT);
                    Msg reply = agent.call(evalCase.input(), context)
                        .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, tenantId)).block(remaining);
                    backend.requireSuccessfulRetrieval();
                    String text = reply == null ? null : reply.getTextContent();
                    if (!StringUtils.hasText(text)) {
                        throw new IllegalStateException("candidate trial returned empty reply: " + evalCase.id());
                    }
                    replies.add(text.trim());
                    if (backend.recalled(candidateRowId)) recalledCaseIds.add(evalCase.id());
                }
            }
        }
        return new TrialResult(replies, recalledCaseIds);
    }

    /** Judge 使用空工具集和独立状态，同样经过计量及安全治理；评分提示词由 QualityEvalRunner 提供。 */
    public Msg judge(Model model, Msg message, TrialIdentity identity, EvalExecutionDeadline deadline) {
        String tenantId = TenantContext.require();
        Duration remaining = deadline.limit(CASE_TIMEOUT);
        try (var toolkit = new ManagedToolkit(); var agent = buildAgent(model, "", 1, toolkit)) {
            return agent.call(List.of(message), context(identity, message.getTextContent(), "知识试评评分"))
                .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, tenantId)).block(remaining);
        }
    }

    private ReActAgent buildAgent(Model model, String systemPrompt, int maxIters, ManagedToolkit toolkit) {
        // 固定只读工具和独立权限状态，不继承工作区审批、授权缓存或线上实验分组。
        var permission = PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
        var builder = ReActAgent.builder().name(TRIAL_AGENT_NAME).sysPrompt(systemPrompt)
            .model(model).toolkit(toolkit).stateStore(new InMemoryAgentStateStore())
            .permissionContext(permission).maxIters(maxIters);
        builder.middleware(agentCallTimingMiddleware);
        builder.middleware(new ModelCompletionMiddleware());
        if (sensitiveWordMiddleware != null) builder.middleware(sensitiveWordMiddleware);
        builder.middleware(promptInjectionGuardMiddleware);
        builder.middleware(maskingMiddleware);
        builder.middleware(indirectInjectionGuardMiddleware);
        return builder.build();
    }

    private RuntimeContext context(TrialIdentity identity, String question, String displayName) {
        var context = RuntimeContext.builder().userId(TRIAL_AGENT_NAME).sessionId(UUID.randomUUID().toString()).build();
        context.put(AgentCallMeta.class, new AgentCallMeta(UUID.randomUUID().toString(), TRIAL_AGENT_NAME,
            identity.agentCode(), displayName, AgentCallSessionType.EVALUATION, question,
            new AgentCallLineage("", "", "", identity.versions())));
        return context;
    }

    /** 每组实际执行使用对应的冻结版本，不从正在编辑的配置或默认运行实例补全。 */
    public record TrialIdentity(String agentCode, EvalVersionBinding versions) { }

    /** 候选实际召回事实与答复独立保存，Judge 高分不能替代目标问题的召回证明。 */
    public record TrialResult(List<String> replies, List<String> candidateRecalledCaseIds) {
        public TrialResult {
            replies = List.copyOf(replies);
            candidateRecalledCaseIds = List.copyOf(candidateRecalledCaseIds);
        }
    }
}
