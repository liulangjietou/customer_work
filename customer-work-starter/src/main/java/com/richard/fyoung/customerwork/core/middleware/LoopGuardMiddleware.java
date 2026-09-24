package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.agent.ConversationTurn;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 智能体转不出来时，让用户有人可找、让运维看得见。
 *
 * <h3>它补的两个洞</h3>
 * <ol>
 *   <li><b>迭代耗尽事件零消费</b>。框架在轮次用尽时发 {@link ExceedMaxItersEvent} 并走
 *       {@code firePreSummary} 生成一段收尾回复——所以用户不会收到空白，但那段话由模型自己写，
 *       <b>不会告诉用户接下来怎么办，也不会把人接进来</b>。用户已经在这一轮里等了十次模型调用
 *       （{@code maxIters} 默认 10），结果是一句含糊的道歉，然后没有下文。
 *       全仓 grep {@code ExceedMaxIters} 零结果——运维也无从知道有多少对话是这样收场的。</li>
 *   <li><b>没有任何死循环征兆检测</b>。同一个工具用同样的参数反复调用，是模型没能从结果里
 *       学到东西的典型信号，而每一轮都是一次真实的模型调用加一次业务后端访问。
 *       在撞上 {@code maxIters} 之前，这件事完全无人知晓。</li>
 * </ol>
 *
 * <h3>为什么全部落在事件流里</h3>
 * <p>工具名、参数、调用结束、迭代耗尽都以事件形式流过 {@code onAgent}，因此不需要在
 * {@code onActing} 与 {@code onAgent} 之间共享状态——那要靠 Reactor Context 跨方法传播，
 * 而「框架对 {@code onActing} 的调用是否落在本方法的 next 链上」是个不该依赖的假设。</p>
 *
 * <h3>去向说明要补两处</h3>
 * <p>只改 {@link AgentResultEvent} 在流式路径上是假性生效：用户端（WS / SSE，以及复用流式内核的同步接口）
 * 与 AG-UI 只把 {@link TextBlockDeltaEvent} 推给前端，拿到过正文就不再看最终结果；而框架的收尾回复
 * 本身就是逐片流式发出的。因此说明既以增量补进收尾文本块，也追加在最终结果上——两类消费方各看各的事件，
 * 各自恰好看到一次。与 {@code SelfCorrectionMiddleware} 注释里记下的是同一个坑，补法与它共用
 * {@link AnswerAppendix}。</p>
 *
 * <h3>只有对用户说话的那次调用才处置</h3>
 * <p>替本轮干活的内部调用（多专家的分诊器 / 专家 / 归纳器、Harness 子智能体）转不出来时，答复不由它交给用户，
 * 它所在的会话也不是用户的会话。这里只记指标与审计，再把去向说明与转人工上报给本轮的组织者
 * （见 {@link ConversationTurn}）——此前在这些调用里转人工，建出来的是一张没人能接到用户的工单，
 * 说明则追加在专家的中间结论上、归纳器改写时可以丢掉。</p>
 *
 * <p>子智能体经 {@code agent_spawn} 同步执行时，它的细粒度事件<b>只</b>转发进父智能体的事件流
 * （带 {@link AgentEvent#getSource()}），不经过它自己的中间件链。于是父智能体这一层是它转不出来的唯一观测点：
 * 这些事件记在子智能体自己的账上、以它的名义记指标与审计，<b>绝不左右父智能体这一轮</b>——
 * 父智能体完全可能拿到子智能体的收尾后照常答完。</p>
 *
 * <h3>重复调用为什么只告警不拦截</h3>
 * <p>拦下重复调用需要让模型知道「别再这样调了」，而中间件改事件流<b>改不了写回
 * {@code AgentState}、下一轮喂给模型的内容</b>（{@code IndirectInjectionGuardMiddleware}
 * 已经踩过并写进了注释）。真正能影响模型的是 {@code onReasoning} 里的瞬态消息注入，
 * 但那条路的效果取决于模型听不听劝，无法在单测里验证——做了也只是"看起来做了"。
 * 因此这里如实只做检测与告警，把引导留给后续单独评估。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class LoopGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(LoopGuardMiddleware.class);

    private static final String CODE_EXHAUSTED = "AGENT-ITERS-EXHAUSTED";
    private static final String CODE_REPEATED = "AGENT-TOOL-CALL-REPEATED";
    private static final String M_EXHAUSTED = "customerwork.agent.iters.exhausted";
    private static final String M_REPEATED = "customerwork.agent.toolcall.repeated";
    private static final String AUDIT_EXHAUSTED = "agent-iters-exhausted";
    private static final String AUDIT_REPEATED = "agent-tool-call-repeated";
    /** 单独补发说明时所用文本块的标识：与框架自己的块区分开，外层按块缓冲的中间件不会把它与别的块混在一起。 */
    private static final String NOTICE_BLOCK_ID = "loop-guard-notice";

    private final boolean enabled;
    private final int repeatedThreshold;
    private final AnswerAppendix notice;
    /** 说明配成空白即表示不追加，两处补发都据此跳过。 */
    private final boolean noticeEnabled;
    private final boolean handoffOnExhausted;
    private final ObjectProvider<HandoffService> handoffProvider;
    private final AuditSink auditSink;
    private final MeterRegistry meterRegistry;

    public LoopGuardMiddleware(CustomerWorkProperties properties,
                               ObjectProvider<HandoffService> handoffProvider,
                               ObjectProvider<AuditSink> auditSinkProvider,
                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        HooksProperties.LoopGuard cfg = properties.getHooks().getLoopGuard();
        this.enabled = cfg.isEnabled();
        this.repeatedThreshold = Math.max(2, cfg.getRepeatedToolCallThreshold());
        this.notice = new AnswerAppendix(cfg.getExhaustedNotice(), NOTICE_BLOCK_ID);
        this.noticeEnabled = StringUtils.hasText(cfg.getExhaustedNotice());
        this.handoffOnExhausted = cfg.isHandoffOnExhausted();
        this.handoffProvider = handoffProvider;
        this.auditSink = auditSinkProvider.getIfAvailable();
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        // 每次调用一份独立状态：中间件是单例，放字段会让并发会话互相串
        TurnState state = new TurnState();
        return next.apply(input).concatMap(event -> observe(agent, ctx, event, state));
    }

    private Flux<AgentEvent> observe(Agent agent, RuntimeContext ctx, AgentEvent event, TurnState state) {
        try {
            String source = event.getSource();
            if (source != null) {
                // 子智能体转发进来的事件：记在它自己的账上，只观测，不左右本轮
                observeForwarded(ctx, source, event,
                    state.forwarded.computeIfAbsent(source, k -> new CallTally()));
                return Flux.just(event);
            }
            if (tally(agentName(agent), ctx, event, state.calls)) {
                return Flux.just(event);
            }
            if (event instanceof ExceedMaxItersEvent exceeded) {
                onExhausted(agent, ctx, exceeded, state);
                return Flux.just(event);
            }
            if (event instanceof TextBlockStartEvent blockStart) {
                state.openTextBlock = blockStart;
                return Flux.just(event);
            }
            if (event instanceof TextBlockDeltaEvent textDelta) {
                if (StringUtils.hasLength(textDelta.getDelta())) {
                    state.textStreamed = true;
                }
                return Flux.just(event);
            }
            if (event instanceof TextBlockEndEvent blockEnd) {
                state.openTextBlock = null;
                return streamNoticeBeforeBlockEnd(blockEnd, state);
            }
            if (event instanceof AgentResultEvent result && state.exhausted) {
                return appendNotice(result, state);
            }
            return Flux.just(event);
        } catch (Exception e) {
            // fail-open：守卫自身故障不该让对话中断，它的定位是观测与兜底而非闸门
            log.error("loop guard failed, passing through, code={}, agent={}",
                CODE_REPEATED, agentName(agent), e);
            return Flux.just(event);
        }
    }

    /** 转发进来的子智能体事件：工具调用照常计数，转不出来只记指标与审计。 */
    private void observeForwarded(RuntimeContext ctx, String source, AgentEvent event, CallTally tally) {
        if (tally(source, ctx, event, tally)) {
            return;
        }
        if (event instanceof ExceedMaxItersEvent exceeded) {
            // 转发链路上父智能体这一层是唯一观测点（子智能体自己的中间件链看不到这个事件），故在这里记一次
            recordExhausted(source, sessionId(ctx), true, exceeded, tally);
        }
    }

    /** 工具调用三件套计入给定的账；不是工具调用事件时返回 false。 */
    private boolean tally(String agentLabel, RuntimeContext ctx, AgentEvent event, CallTally tally) {
        if (event instanceof ToolCallStartEvent start) {
            tally.toolNames.put(keyOf(start.getToolCallId()), start.getToolCallName());
            return true;
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            if (delta.getDelta() != null) {
                tally.arguments.computeIfAbsent(keyOf(delta.getToolCallId()), k -> new StringBuilder())
                    .append(delta.getDelta());
            }
            return true;
        }
        if (event instanceof ToolCallEndEvent end) {
            countCall(agentLabel, ctx, end, tally);
            return true;
        }
        return false;
    }

    /**
     * 一次工具调用完成：按「工具名 + 参数」计数。
     *
     * <p>参数一并计入签名是关键——同一个工具用<b>不同</b>参数连查五个订单是正常业务，
     * 只按工具名计数会把它误报成循环，而误报几次之后这个指标就没人看了。</p>
     */
    private void countCall(String agentLabel, RuntimeContext ctx, ToolCallEndEvent end, CallTally tally) {
        String callId = keyOf(end.getToolCallId());
        String name = end.getToolCallName() != null
            ? end.getToolCallName() : tally.toolNames.get(callId);
        StringBuilder args = tally.arguments.remove(callId);
        tally.toolNames.remove(callId);
        if (name == null) {
            return;
        }
        String signature = name + "|" + (args == null ? "" : args.toString());
        int times = tally.callCounts.merge(signature, 1, Integer::sum);
        if (times != repeatedThreshold) {
            // 只在恰好触达阈值的那一次告警：继续涨下去每轮报一次，会把日志刷成噪音
            return;
        }
        metric(M_REPEATED);
        log.error("repeated identical tool call detected, code={}, agent={}, session={}, tool={}, times={}",
            CODE_REPEATED, agentLabel, sessionId(ctx), name, times);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("agent", agentLabel);
        fields.put("session", sessionId(ctx));
        fields.put("tool", name);
        fields.put("times", times);
        audit(AUDIT_REPEATED, fields);
    }

    /**
     * 轮次用尽：记指标与审计，并把人接进来。
     *
     * <p>内部调用只记录、再上报给本轮的组织者：去向说明要接在用户真正收到的最终答复上，
     * 转人工要落在用户自己的会话上，而这两样它都不掌握。</p>
     */
    private void onExhausted(Agent agent, RuntimeContext ctx, ExceedMaxItersEvent event, TurnState state) {
        ConversationTurn owner = ConversationTurn.delegatedBy(ctx);
        recordExhausted(agentName(agent), sessionId(ctx), owner != null, event, state.calls);
        if (owner != null) {
            owner.escalate(new ConversationTurn.Escalation(agentName(agent),
                noticeEnabled ? notice.text() : null,
                handoffOnExhausted ? handoffReason(event.getMaxIters()) : null));
            return;
        }
        state.exhausted = true;
        handoff(sessionId(ctx), event.getMaxIters());
    }

    /** 每一次转不出来的 Agent 调用恰好记一次：谁转不出来就以谁的名义记。 */
    private void recordExhausted(String agentLabel, String session, boolean delegated,
                                 ExceedMaxItersEvent event, CallTally tally) {
        metric(M_EXHAUSTED);
        log.error("agent iterations exhausted, code={}, agent={}, session={}, delegated={}, maxIters={}, tools={}",
            CODE_EXHAUSTED, agentLabel, session, delegated, event.getMaxIters(), tally.callCounts.keySet());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("agent", agentLabel);
        fields.put("session", session);
        fields.put("delegated", delegated);
        fields.put("maxIters", event.getMaxIters());
        fields.put("currentIter", event.getCurrentIter());
        fields.put("distinctToolCalls", tally.callCounts.size());
        audit(AUDIT_EXHAUSTED, fields);
    }

    /**
     * 流式消费方看到的说明：补进收尾文本块，赶在它结束之前。
     *
     * <p>轮次用尽之后框架只剩一次模型调用——生成收尾回复——它的文本块就是本轮最后一个文本块。
     * 必须补在块结束之前：结束之后再往同一块追加，AG-UI 会收到已结束消息的内容；外层敏感词过滤按块缓冲、
     * 块结束才放行尾巴，也等不到下一次放行。补在同一块里还有一层好处：AG-UI 落库只取最后一条消息，
     * 另起一块会让历史里只剩说明、丢了收尾正文。</p>
     */
    private Flux<AgentEvent> streamNoticeBeforeBlockEnd(TextBlockEndEvent blockEnd, TurnState state) {
        if (!state.exhausted || state.noticeStreamed || !noticeEnabled) {
            return Flux.just(blockEnd);
        }
        state.noticeStreamed = true;
        return Flux.just(notice.into(blockEnd.getReplyId(), blockEnd.getBlockId()), blockEnd);
    }

    /**
     * 只读最终结果的消费方（{@code call()}：IM 渠道、评测）看到的说明：追加到收尾回复上。
     *
     * <p>框架此时已经生成了收尾，追加而不是替换：那段话里可能有对用户有用的中间结论，
     * 整段丢掉等于把十轮的成果一起扔了。也不重建消息：框架标在上面的 {@code MAX_ITERATIONS}
     * 要原样交给外层的终止采集，前端据此显示「答复尚未完成」，重建会把它抹成正常结束。</p>
     *
     * <p>收尾没能正常结束一个文本块（模型调用失败）、而用户此前又已收到过正文时，流式消费方不会再看
     * 最终结果，这里在它之前把说明补上。一个正文增量都没出过则不补：流式消费方会用最终结果补全文，
     * 说明已经在里面了。</p>
     */
    private Flux<AgentEvent> appendNotice(AgentResultEvent result, TurnState state) {
        if (state.resultRewritten || !noticeEnabled) {
            return Flux.just(result);
        }
        state.resultRewritten = true;
        AgentResultEvent rewritten = new AgentResultEvent(notice.appendTo(result.getResult()));
        if (state.noticeStreamed || !state.textStreamed) {
            return Flux.just(rewritten);
        }
        state.noticeStreamed = true;
        return noticeBlock(state).concatWith(Flux.just(rewritten));
    }

    /**
     * 收尾没有正常结束的文本块时补发说明：收尾模型中途失败留下的悬空块补进并替它结束，
     * 收尾一个字都没吐时单独起一个完整的块（见 {@link AnswerAppendix#closingBlock}）。
     */
    private Flux<AgentEvent> noticeBlock(TurnState state) {
        TextBlockStartEvent dangling = state.openTextBlock;
        state.openTextBlock = null;
        return Flux.fromIterable(notice.closingBlock(dangling));
    }

    private void handoff(String sessionId, int maxIters) {
        if (!handoffOnExhausted || sessionId == null || sessionId.isBlank()) {
            return;
        }
        HandoffService handoffService = handoffProvider.getIfAvailable();
        if (handoffService == null) {
            return;
        }
        try {
            handoffService.create(sessionId, handoffReason(maxIters));
        } catch (Exception e) {
            log.error("handoff on iterations exhausted failed, code={}, session={}",
                CODE_EXHAUSTED, sessionId, e);
        }
    }

    private static String handoffReason(int maxIters) {
        return "自动应答轮次用尽（maxIters=" + maxIters + "），需人工接手";
    }

    private void audit(String type, Map<String, Object> fields) {
        if (auditSink == null) {
            return;
        }
        try {
            fields.put("ts", System.currentTimeMillis());
            auditSink.record(type, fields);
        } catch (Exception e) {
            log.error("loop guard audit record failed, code={}, type={}", CODE_REPEATED, type, e);
        }
    }

    private void metric(String name) {
        if (meterRegistry != null) {
            Counter.builder(name).register(meterRegistry).increment();
        }
    }

    private String keyOf(String toolCallId) {
        return toolCallId == null || toolCallId.isBlank() ? "" : toolCallId;
    }

    private String sessionId(RuntimeContext ctx) {
        return ctx == null ? null : ctx.getSessionId();
    }

    private String agentName(Agent agent) {
        return agent == null ? "?" : agent.getName();
    }

    /** 一个 Agent 在本轮里的工具调用账：按调用 id 拼参数，按「工具名 + 参数」计次。 */
    private static final class CallTally {
        private final Map<String, String> toolNames = new HashMap<>();
        private final Map<String, StringBuilder> arguments = new HashMap<>();
        private final Map<String, Integer> callCounts = new HashMap<>();
    }

    /** 一轮对话的观测状态。 */
    private static final class TurnState {
        private final CallTally calls = new CallTally();
        /** 转发进来的子智能体事件，按来源各记一本账，与本轮自己的调用互不相混。 */
        private final Map<String, CallTally> forwarded = new HashMap<>();
        private boolean exhausted;
        /** 本轮是否流出过非空正文：流式消费方据此决定还看不看最终结果。 */
        private boolean textStreamed;
        /** 已开始、尚未结束的文本块；收尾模型中途失败时它会一直悬空。 */
        private TextBlockStartEvent openTextBlock;
        private boolean noticeStreamed;
        private boolean resultRewritten;
    }

    /** 顺序契约见 {@link MiddlewareOrders}：观测整轮，需看到工具调用与最终结果。 */
    @Override
    public int order() {
        return MiddlewareOrders.LOOP_GUARD;
    }
}
