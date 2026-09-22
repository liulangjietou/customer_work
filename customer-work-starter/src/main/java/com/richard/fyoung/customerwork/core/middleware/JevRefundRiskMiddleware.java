package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionService;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTurnText;
import com.richard.fyoung.customerwork.capability.typesafe.NoulVerdict;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Jev 退款风险判定 → 高风险时额外转人工。
 *
 * <p><b>它不改变审批</b>：退款本就 100% 生成待人工审批单（{@code submitRefund} → {@code PendingApprovalService}），
 * 没有任何自动放行路径，Jev 也不会新增一条。这里只解决一个问题：高风险退款此前只是静静躺在审批队列里，
 * 现在在工单照常生成的同时，立刻拉坐席介入会话。</p>
 *
 * <p><b>Jev 只看得到对话文本</b>（用户原话 + 退款金额与原因），看不到订单历史、退款频次、账户风险这类
 * 结构化风控数据。它判的是「文本语义风险」（威胁、欺诈话术、描述与金额不符），不是交易风控。</p>
 *
 * <p>判定在工具执行之前进行，但无论结论如何工具都照常执行——本类从不拦截、改写或延后退款工具调用。</p>
 */
@Component
public class JevRefundRiskMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(JevRefundRiskMiddleware.class);

    private static final String CODE_FAIL = "TYPESAFE-REFUND-RISK-FAIL";
    private static final String CODE_HANDOFF_FAIL = "TYPESAFE-REFUND-RISK-HANDOFF-FAIL";
    private static final String TITLE = "退款风险判定";
    private static final String PARAM_AMOUNT = "amount";
    private static final String PARAM_REASON = "reason";
    private static final String HANDOFF_REASON = "Jev 判定退款请求存在高风险信号（概率 %.2f），需坐席立即介入";

    private final ObjectProvider<JevDecisionService> decisionProvider;
    private final ObjectProvider<HandoffService> handoffProvider;
    private final JevRunMode mode;

    @Autowired
    public JevRefundRiskMiddleware(ObjectProvider<JevDecisionService> decisionProvider,
                                   ObjectProvider<HandoffService> handoffProvider) {
        this(decisionProvider, handoffProvider, JevRunMode.LIVE);
    }

    public JevRefundRiskMiddleware(ObjectProvider<JevDecisionService> decisionProvider,
                                   ObjectProvider<HandoffService> handoffProvider, JevRunMode mode) {
        this.decisionProvider = decisionProvider;
        this.handoffProvider = handoffProvider;
        this.mode = mode;
    }

    /** 本轮开始时记住用户原话：工具阶段的入参里没有它。 */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (enabled() != null) {
            JevTurnText.resolve(ctx, input.msgs());
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        JevDecisionService jev = enabled();
        ToolUseBlock refundCall = jev == null ? null : firstRefundCall(jev, input);
        if (refundCall == null) {
            return next.apply(input);
        }
        Map<String, Object> args = refundCall.getInput() == null ? Map.of() : refundCall.getInput();
        return jev.judgeRefundRisk(JevTurnText.recall(ctx), asText(args.get(PARAM_AMOUNT)),
                asText(args.get(PARAM_REASON)))
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(verdict -> resolve(jev, ctx, verdict))
            .defaultIfEmpty(Outcome.SKIPPED)
            .onErrorResume(e -> {
                log.error("jev refund risk failed, passing through, code={}", CODE_FAIL, e);
                return Mono.just(Outcome.SKIPPED);
            })
            .flatMapMany(outcome -> events(outcome).concatWith(Flux.defer(() -> next.apply(input))));
    }

    private Mono<Outcome> resolve(JevDecisionService jev, RuntimeContext ctx, Optional<NoulVerdict> maybe) {
        if (maybe.isEmpty()) {
            jev.recordDecision(JevDecisionEvent.POINT_REFUND_RISK, JevDecisionEvent.RESULT_DEGRADED, mode);
            return Mono.just(new Outcome(null, false, false, false));
        }
        NoulVerdict verdict = maybe.get();
        boolean highRisk = verdict.atLeast(jev.properties().getRefundRisk().getMinProbability());
        jev.recordDecision(JevDecisionEvent.POINT_REFUND_RISK,
            highRisk ? JevDecisionEvent.RESULT_HIGH_RISK : JevDecisionEvent.RESULT_NORMAL, mode);
        if (!highRisk || !mode.act()) {
            return Mono.just(new Outcome(verdict, highRisk, false, false));
        }
        HandoffService handoff = handoffProvider.getIfAvailable();
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (handoff == null || !StringUtils.hasText(sessionId)) {
            return Mono.just(new Outcome(verdict, true, false, false));
        }
        return Mono.fromCallable(() -> {
                handoff.create(sessionId, String.format(HANDOFF_REASON, verdict.probability()));
                return new Outcome(verdict, true, true, false);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.error("jev refund risk handoff failed, code={}, session={}", CODE_HANDOFF_FAIL, sessionId, e);
                return Mono.just(new Outcome(verdict, true, false, false));
            });
    }

    private Flux<AgentEvent> events(Outcome outcome) {
        if (!mode.emit() || outcome.skipped()) {
            return Flux.empty();
        }
        if (outcome.verdict() == null) {
            return Flux.just(JevDecisionEvent.degraded(JevDecisionEvent.POINT_REFUND_RISK, TITLE));
        }
        String verdict = String.format("存在需坐席介入的风险信号的概率 %.2f", outcome.verdict().probability());
        String action = outcome.highRisk()
            ? "额外转人工坐席介入（审批单照常生成，不做任何放行）" : "无需额外处置（审批单照常生成）";
        return Flux.just(JevDecisionEvent.decided(mode, JevDecisionEvent.POINT_REFUND_RISK, TITLE, verdict, action,
            outcome.handedOff(), outcome.verdict().probability(), outcome.verdict().model(),
            outcome.verdict().latencyMs()));
    }

    private JevDecisionService enabled() {
        JevDecisionService jev = decisionProvider.getIfAvailable();
        return jev != null && jev.properties().getRefundRisk().isEnabled() ? jev : null;
    }

    private static ToolUseBlock firstRefundCall(JevDecisionService jev, ActingInput input) {
        if (input == null || CollectionUtils.isEmpty(input.toolCalls())) {
            return null;
        }
        for (ToolUseBlock call : input.toolCalls()) {
            if (call != null && jev.properties().getRefundRisk().getRefundTools().contains(call.getName())) {
                return call;
            }
        }
        return null;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 顺序契约见 {@link MiddlewareOrders}：紧挨工具级人工确认的内侧，外层授权拒绝时退款根本不会发生。 */
    @Override
    public int order() {
        return MiddlewareOrders.JEV_REFUND_RISK;
    }

    /**
     * @param verdict   null 表示 Jev 本轮没给出决策
     * @param highRisk  是否达到高风险门槛
     * @param handedOff 是否真的转人工成功
     * @param skipped   本类自身出错：既不处置也不展示
     */
    record Outcome(NoulVerdict verdict, boolean highRisk, boolean handedOff, boolean skipped) {
        static final Outcome SKIPPED = new Outcome(null, false, false, true);
    }
}
