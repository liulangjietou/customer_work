package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionService;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTurnText;
import com.richard.fyoung.customerwork.capability.typesafe.TurnDecision;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import com.richard.fyoung.customerwork.tool.ToolRegistrar;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Jev 意图判定 → 本轮工具面收窄。
 *
 * <h3>为什么现在可以做</h3>
 * <p>工具 schema 约占上下文预算的一半（见 {@code ToolSurfaceCostTest}），而且这笔钱每一轮都付。
 * 此前否决「按意图动态激活」的理由是<b>意图分类没有置信度</b>，判不准时无法兜底。
 * Jev 的 Choice 自带置信度，判不准（低于门槛）或判为 other 时就不收窄。</p>
 *
 * <h3>为什么改 ReasoningInput 而不是改激活组</h3>
 * <p>框架会用会话状态里的 activatedGroups <b>全量覆盖</b> Toolkit 的激活组（见
 * {@code DefaultActiveGroupsToolkit}）。在这里改激活组，收窄结果会被持久化进会话、下一轮继续生效——
 * 那正是项目踩过的「业务工具组被整体清空」。{@link ReasoningInput#tools()} 只决定本次模型调用
 * 看得到哪些工具，不写回任何状态；工具本身仍在 Toolkit 里，模型凭历史调用也照样能执行，不会失败。</p>
 *
 * <p>三类工具永远保留：映射到的业务组、转人工组（把它收掉等于把用户困在智能体里）、
 * 未分组的基础工具（如 Harness 自带的文件与计划工具）。拿不到 Toolkit、收窄后为空、
 * 或者没有任何工具被收掉时，原样透传。</p>
 */
@Component
public class JevToolScopeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(JevToolScopeMiddleware.class);

    private static final String CODE_FAIL = "TYPESAFE-TOOL-SCOPE-FAIL";
    private static final String CTX_SCOPE = "typesafe.tool.scope";
    private static final String TITLE = "意图识别 · 工具收窄";

    private final ObjectProvider<JevDecisionService> decisionProvider;
    private final JevRunMode mode;

    @Autowired
    public JevToolScopeMiddleware(ObjectProvider<JevDecisionService> decisionProvider) {
        this(decisionProvider, JevRunMode.LIVE);
    }

    public JevToolScopeMiddleware(ObjectProvider<JevDecisionService> decisionProvider, JevRunMode mode) {
        this.decisionProvider = decisionProvider;
        this.mode = mode;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        JevDecisionService jev = decisionProvider.getIfAvailable();
        if (jev == null || !jev.properties().getToolScope().isEnabled()) {
            return next.apply(input);
        }
        String userText = JevTurnText.resolve(ctx, input.msgs());
        if (!StringUtils.hasText(userText)) {
            return next.apply(input);
        }
        // 判定链路必须恰好产出一个元素，next 才会恰好被调一次。不能用 switchIfEmpty 兜底：
        // 下游返回空事件流时它同样会触发，Agent 会被执行第二遍
        return jev.decideTurn(ctx, userText)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .map(decision -> {
                Scope scope = decision.map(d -> new Scope(d, d.scopedGroups(jev.properties().getToolScope())
                    .orElse(null))).orElse(null);
                jev.recordDecision(JevDecisionEvent.POINT_TOOL_SCOPE,
                    scope == null ? JevDecisionEvent.RESULT_DEGRADED
                        : scope.groups() == null ? JevDecisionEvent.RESULT_KEPT : JevDecisionEvent.RESULT_NARROWED,
                    mode);
                return ScopeResult.of(scope);
            })
            .onErrorResume(e -> {
                log.error("jev tool scope failed, passing through, code={}", CODE_FAIL, e);
                return Mono.just(ScopeResult.SKIPPED);
            })
            .flatMapMany(result -> {
                if (ctx != null && result.scope() != null) {
                    ctx.put(CTX_SCOPE, Scope.class, result.scope());
                }
                Flux<AgentEvent> events = result.skipped() ? Flux.empty() : events(jev, ctx, result.scope());
                return events.concatWith(Flux.defer(() -> next.apply(input)));
            });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!mode.act() || ctx == null) {
            return next.apply(input);
        }
        Scope scope = ctx.get(CTX_SCOPE, Scope.class);
        if (scope == null || scope.groups() == null) {
            return next.apply(input);
        }
        try {
            List<ToolSchema> narrowed = narrow(toolkitOf(agent), input.tools(), scope.groups());
            return narrowed == null ? next.apply(input)
                : next.apply(new ReasoningInput(input.messages(), narrowed, input.options()));
        } catch (Exception e) {
            log.error("jev tool scope narrowing failed, passing through, code={}", CODE_FAIL, e);
            return next.apply(input);
        }
    }

    /**
     * 收窄工具面；不需要或不能收窄时返回 null。包级可见便于离线单测。
     *
     * @param keepGroups 本轮保留的业务组
     */
    static List<ToolSchema> narrow(Toolkit toolkit, List<ToolSchema> tools, List<String> keepGroups) {
        if (toolkit == null || CollectionUtils.isEmpty(tools)) {
            return null;
        }
        Set<String> grouped = new HashSet<>();
        Set<String> allowed = new HashSet<>();
        for (String group : toolkit.getActiveGroups()) {
            ToolGroup toolGroup = toolkit.getToolGroup(group);
            if (toolGroup == null) {
                continue;
            }
            grouped.addAll(toolGroup.getTools());
            if (keepGroups.contains(group) || ToolRegistrar.GROUP_HUMAN.equals(group)) {
                allowed.addAll(toolGroup.getTools());
            }
        }
        List<ToolSchema> narrowed = tools.stream()
            .filter(tool -> allowed.contains(tool.getName()) || !grouped.contains(tool.getName()))
            .collect(Collectors.toList());
        if (narrowed.isEmpty() || narrowed.size() == tools.size()) {
            return null;
        }
        return narrowed;
    }

    private Flux<AgentEvent> events(JevDecisionService jev, RuntimeContext ctx, Scope scope) {
        if (!mode.emit()) {
            return Flux.empty();
        }
        if (scope == null) {
            return JevEscalationMiddleware.claimTurnDegraded(ctx)
                ? Flux.just(JevDecisionEvent.degraded(JevDecisionEvent.POINT_TURN, JevDecisionEvent.TITLE_TURN))
                : Flux.empty();
        }
        TurnDecision decision = scope.decision();
        TypeSafeProperties cfg = jev.properties();
        String verdict = decision.intent() == null ? "Jev 未返回意图判定"
            : "意图：" + TurnDecision.describeIntent(cfg.getIntentCriteria(), decision.intent().choice());
        String action = scope.groups() != null
            ? "本轮只保留工具组：" + String.join("、", scope.groups()) + "（转人工组与基础工具始终保留）"
            : "不收窄（" + keepReason(decision, cfg.getToolScope()) + "）";
        return Flux.just(JevDecisionEvent.decided(mode, JevDecisionEvent.POINT_TOOL_SCOPE, TITLE, verdict, action,
            mode.act() && scope.groups() != null, decision.intent() == null ? null : decision.intent().confidence(),
            decision.model(), decision.latencyMs()));
    }

    private static String keepReason(TurnDecision decision, TypeSafeProperties.ToolScope cfg) {
        if (decision.intent() == null) {
            return "无意图判定";
        }
        if (TurnDecision.INTENT_OTHER.equals(decision.intent().choice())) {
            return "意图无法归类";
        }
        if (decision.intent().confidence() < cfg.getMinConfidence()) {
            return String.format("置信度低于 %.2f", cfg.getMinConfidence());
        }
        return "该意图未配置工具组映射";
    }

    private static Toolkit toolkitOf(Agent agent) {
        return agent instanceof ReActAgent reActAgent ? reActAgent.getToolkit() : null;
    }

    /** 顺序契约见 {@link MiddlewareOrders}：必须在上下文预算之外，预算才按收窄后的工具面计算。 */
    @Override
    public int order() {
        return MiddlewareOrders.JEV_TOOL_SCOPE;
    }

    /**
     * 本轮收窄结论。
     *
     * @param groups 保留的业务组；null 表示不收窄
     */
    record Scope(TurnDecision decision, List<String> groups) {
    }

    /**
     * 判定环节的产物，保证链路上恰好一个元素。
     *
     * @param scope   null 表示 Jev 本轮没给出决策（降级）
     * @param skipped 本类自身出错：既不收窄也不展示
     */
    record ScopeResult(Scope scope, boolean skipped) {
        static final ScopeResult SKIPPED = new ScopeResult(null, true);

        static ScopeResult of(Scope scope) {
            return new ScopeResult(scope, false);
        }
    }
}
