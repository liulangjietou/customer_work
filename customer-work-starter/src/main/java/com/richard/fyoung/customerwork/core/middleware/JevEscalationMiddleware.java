package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionService;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTurnText;
import com.richard.fyoung.customerwork.capability.typesafe.SystemOneAnswer;
import com.richard.fyoung.customerwork.capability.typesafe.TurnDecision;
import com.richard.fyoung.customerwork.capability.typesafe.TurnDecision.EscalationAction;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Jev 情绪/升级判定 → 两段式转人工。
 *
 * <h3>它补的是什么</h3>
 * <p>此前转人工完全靠模型自己决定调不调 {@code transferToHuman}。用户已经暴怒而模型还在讲政策，
 * 链路上不会有任何信号。这里用一个客观判定兜底：</p>
 * <ul>
 *   <li><b>高置信处于最高档</b>（强烈愤怒 / 明确要求人工）：直接建工单转人工，再告诉模型已转接、让它安抚；</li>
 *   <li><b>明显不满</b>：只提示模型考虑转人工，最终仍由模型决定；</li>
 *   <li>其余不处置。</li>
 * </ul>
 *
 * <h3>为什么判定在 onAgent、注入在 onReasoning</h3>
 * <p>{@code onAgent} 每轮只进一次，自动转人工这个副作用放在这里天然不会重复执行；
 * {@code onReasoning} 在 ReAct 循环里每迭代一次都会进，提示要每次都注入才能让每次推理都看得到。
 * 两处经 {@link RuntimeContext}（每次调用新建一份）交接，不依赖 Reactor Context 的传播。</p>
 *
 * <p>提示走瞬态消息（与 {@code KnowledgeInjectionMiddleware} 同一手法），不写回会话历史。
 * 影子模式下<b>不注入任何内容</b>：后台影子只展示判定，绝不改变模型的行为。</p>
 */
@Component
public class JevEscalationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(JevEscalationMiddleware.class);

    private static final String CODE_FAIL = "TYPESAFE-ESCALATION-FAIL";
    private static final String CODE_HANDOFF_FAIL = "TYPESAFE-ESCALATION-HANDOFF-FAIL";
    private static final String CTX_OUTCOME = "typesafe.escalation.outcome";
    /** 入站决策的降级事件由情绪与工具收窄两个决策点共享，一轮只展示一次。 */
    static final String CTX_TURN_DEGRADED_EMITTED = "typesafe.turn.degraded.emitted";

    private static final String TITLE = "情绪升级判定";
    private static final String HANDOFF_REASON = "Jev 判定用户情绪强烈或明确要求人工";
    private static final String REMINDER_NAME = "system";
    private static final String REMINDER_KIND = "jev_escalation";
    private static final String HANDED_OFF_HINT =
        "【系统提示】检测到用户情绪强烈或明确要求人工，系统已为其转接人工坐席（工单号 %s）。"
            + "请在回复中告知用户正在为其转接人工并安抚情绪，无需再调用转人工工具。";
    private static final String CONSIDER_HINT =
        "【系统提示】用户情绪明显不满。若本轮无法直接解决其诉求，请调用转人工工具为其转接人工坐席。";

    private final ObjectProvider<JevDecisionService> decisionProvider;
    private final ObjectProvider<HandoffService> handoffProvider;
    private final JevRunMode mode;

    /** Spring 装配给 C 端：一律 LIVE，不发决策事件。 */
    @Autowired
    public JevEscalationMiddleware(ObjectProvider<JevDecisionService> decisionProvider,
                                   ObjectProvider<HandoffService> handoffProvider) {
        this(decisionProvider, handoffProvider, JevRunMode.LIVE);
    }

    public JevEscalationMiddleware(ObjectProvider<JevDecisionService> decisionProvider,
                                   ObjectProvider<HandoffService> handoffProvider, JevRunMode mode) {
        this.decisionProvider = decisionProvider;
        this.handoffProvider = handoffProvider;
        this.mode = mode;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        JevDecisionService jev = decisionProvider.getIfAvailable();
        if (jev == null || !jev.properties().getEscalation().isEnabled()) {
            return next.apply(input);
        }
        String userText = JevTurnText.resolve(ctx, input.msgs());
        if (!StringUtils.hasText(userText)) {
            return next.apply(input);
        }
        // 两条不变量，任何一条破了都会伤到对话本身：
        // ① 判定链路必须恰好产出一个元素——空了 next 永远不会被调，这一轮直接没有回复；
        // ② onErrorResume 只包本类自己的判定，绝不能包到 next 上——那会吞掉 Agent 本身的错误
        return jev.decideTurn(ctx, userText)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(decision -> resolve(jev, ctx, decision))
            .defaultIfEmpty(Outcome.SKIPPED)
            .onErrorResume(e -> {
                log.error("jev escalation failed, passing through, code={}", CODE_FAIL, e);
                return Mono.just(Outcome.SKIPPED);
            })
            .flatMapMany(outcome -> {
                if (ctx != null) {
                    ctx.put(CTX_OUTCOME, Outcome.class, outcome);
                }
                return events(jev, ctx, outcome).concatWith(Flux.defer(() -> next.apply(input)));
            });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!mode.act() || ctx == null) {
            return next.apply(input);
        }
        Outcome outcome = ctx.get(CTX_OUTCOME, Outcome.class);
        String hint = outcome == null ? null : outcome.hint();
        return hint == null ? next.apply(input) : next.apply(withHint(input, hint));
    }

    /** 判定 + （LIVE 时）执行自动转人工。 */
    private Mono<Outcome> resolve(JevDecisionService jev, RuntimeContext ctx, Optional<TurnDecision> maybe) {
        if (maybe.isEmpty()) {
            jev.recordDecision(JevDecisionEvent.POINT_ESCALATION, JevDecisionEvent.RESULT_DEGRADED, mode);
            return Mono.just(Outcome.degraded());
        }
        TurnDecision decision = maybe.get();
        EscalationAction action = decision.escalationAction(jev.properties().getEscalation(), jev.escalationTopLevel());
        jev.recordDecision(JevDecisionEvent.POINT_ESCALATION, action.name().toLowerCase(), mode);
        if (action != EscalationAction.AUTO_HANDOFF || !mode.act()) {
            return Mono.just(new Outcome(action, decision, null, false));
        }
        HandoffService handoff = handoffProvider.getIfAvailable();
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (handoff == null || !StringUtils.hasText(sessionId)) {
            // 转不了就退回提示模型：模型手里的转人工工具自己会处理没有工单服务的情况
            return Mono.just(new Outcome(EscalationAction.HINT, decision, null, false));
        }
        // 建工单是一次落库，丢到 boundedElastic；失败退回提示模型，不影响本轮对话
        return Mono.fromCallable(() -> handoff.create(sessionId, HANDOFF_REASON).getId())
            .subscribeOn(Schedulers.boundedElastic())
            .map(ticketId -> new Outcome(EscalationAction.AUTO_HANDOFF, decision, ticketId, true))
            .onErrorResume(e -> {
                log.error("jev auto handoff failed, fallback to hint, code={}, session={}", CODE_HANDOFF_FAIL,
                    sessionId, e);
                return Mono.just(new Outcome(EscalationAction.HINT, decision, null, false));
            });
    }

    private Flux<AgentEvent> events(JevDecisionService jev, RuntimeContext ctx, Outcome outcome) {
        if (!mode.emit() || outcome.skipped()) {
            return Flux.empty();
        }
        if (outcome.decision() == null) {
            return claimTurnDegraded(ctx)
                ? Flux.just(JevDecisionEvent.degraded(JevDecisionEvent.POINT_TURN, JevDecisionEvent.TITLE_TURN))
                : Flux.empty();
        }
        SystemOneAnswer.Score score = outcome.decision().escalation();
        String verdict = score == null ? "Jev 未返回情绪判定"
            : "最可能：" + jev.escalationLevelLabel(score.mostLikelyLevel())
                + String.format("（档位期望 %.2f / 最高 %d）", score.score(), jev.escalationTopLevel());
        return Flux.just(JevDecisionEvent.decided(mode, JevDecisionEvent.POINT_ESCALATION, TITLE, verdict,
            actionLabel(outcome), outcome.executed(), score == null ? null : score.confidence(),
            outcome.decision().model(), outcome.decision().latencyMs()));
    }

    private static String actionLabel(Outcome outcome) {
        return switch (outcome.action()) {
            case AUTO_HANDOFF -> outcome.ticketId() == null ? "直接转人工坐席" : "直接转人工坐席，工单号 " + outcome.ticketId();
            case HINT -> "提示模型考虑转人工";
            case NONE -> "无需处置";
        };
    }

    /** 抢占本轮入站降级事件的展示名额：情绪与工具收窄共用一次调用，失败只展示一次。 */
    static boolean claimTurnDegraded(RuntimeContext ctx) {
        if (ctx == null) {
            return true;
        }
        if (ctx.get(CTX_TURN_DEGRADED_EMITTED, Boolean.class) != null) {
            return false;
        }
        ctx.put(CTX_TURN_DEGRADED_EMITTED, Boolean.class, Boolean.TRUE);
        return true;
    }

    private static ReasoningInput withHint(ReasoningInput input, String hint) {
        List<Msg> messages = CollectionUtils.isEmpty(input.messages())
            ? new ArrayList<>() : new ArrayList<>(input.messages());
        messages.add(Msg.builder()
            .role(MsgRole.USER)
            .name(REMINDER_NAME)
            .content(TextBlock.builder().text(hint).build())
            .metadata(Map.of(Msg.METADATA_SYNTHETIC, true, Msg.METADATA_REMINDER_KIND, REMINDER_KIND))
            .build());
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    /** 顺序契约见 {@link MiddlewareOrders}：对话控制，排在上下文组装之前。 */
    @Override
    public int order() {
        return MiddlewareOrders.JEV_ESCALATION;
    }

    /**
     * 本轮判定结果。
     *
     * @param executed 自动转人工是否真的执行成功
     */
    record Outcome(EscalationAction action, TurnDecision decision, String ticketId, boolean executed,
                   boolean skipped) {

        Outcome(EscalationAction action, TurnDecision decision, String ticketId, boolean executed) {
            this(action, decision, ticketId, executed, false);
        }

        static Outcome degraded() {
            return new Outcome(EscalationAction.NONE, null, null, false, false);
        }

        /** 本类自身出错：既不处置也不展示。 */
        static final Outcome SKIPPED = new Outcome(EscalationAction.NONE, null, null, false, true);

        /** 要注入给模型的提示；null 表示不注入。 */
        String hint() {
            if (action == EscalationAction.AUTO_HANDOFF && executed) {
                return String.format(HANDED_OFF_HINT, ticketId);
            }
            return action == EscalationAction.HINT ? CONSIDER_HINT : null;
        }
    }
}
