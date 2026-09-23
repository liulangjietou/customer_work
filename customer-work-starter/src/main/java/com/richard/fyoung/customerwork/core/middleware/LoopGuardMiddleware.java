package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 各自恰好看到一次。与 {@code SelfCorrectionMiddleware} 注释里记下的是同一个坑。</p>
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
    private final String exhaustedNotice;
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
        this.exhaustedNotice = cfg.getExhaustedNotice();
        this.noticeEnabled = StringUtils.hasText(exhaustedNotice);
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
            if (event instanceof ToolCallStartEvent start) {
                state.toolNames.put(keyOf(start.getToolCallId()), start.getToolCallName());
                return Flux.just(event);
            }
            if (event instanceof ToolCallDeltaEvent delta) {
                if (delta.getDelta() != null) {
                    state.arguments.computeIfAbsent(keyOf(delta.getToolCallId()), k -> new StringBuilder())
                        .append(delta.getDelta());
                }
                return Flux.just(event);
            }
            if (event instanceof ToolCallEndEvent end) {
                countCall(agent, ctx, end, state);
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

    /**
     * 一次工具调用完成：按「工具名 + 参数」计数。
     *
     * <p>参数一并计入签名是关键——同一个工具用<b>不同</b>参数连查五个订单是正常业务，
     * 只按工具名计数会把它误报成循环，而误报几次之后这个指标就没人看了。</p>
     */
    private void countCall(Agent agent, RuntimeContext ctx, ToolCallEndEvent end, TurnState state) {
        String callId = keyOf(end.getToolCallId());
        String name = end.getToolCallName() != null
            ? end.getToolCallName() : state.toolNames.get(callId);
        StringBuilder args = state.arguments.remove(callId);
        state.toolNames.remove(callId);
        if (name == null) {
            return;
        }
        String signature = name + "|" + (args == null ? "" : args.toString());
        int times = state.callCounts.merge(signature, 1, Integer::sum);
        if (times != repeatedThreshold) {
            // 只在恰好触达阈值的那一次告警：继续涨下去每轮报一次，会把日志刷成噪音
            return;
        }
        metric(M_REPEATED);
        log.error("repeated identical tool call detected, code={}, agent={}, session={}, tool={}, times={}",
            CODE_REPEATED, agentName(agent), sessionId(ctx), name, times);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("agent", agentName(agent));
        fields.put("session", sessionId(ctx));
        fields.put("tool", name);
        fields.put("times", times);
        audit(AUDIT_REPEATED, fields);
    }

    /** 轮次用尽：记指标与审计，并把人接进来。 */
    private void onExhausted(Agent agent, RuntimeContext ctx, ExceedMaxItersEvent event, TurnState state) {
        state.exhausted = true;
        metric(M_EXHAUSTED);
        String session = sessionId(ctx);
        log.error("agent iterations exhausted, code={}, agent={}, session={}, maxIters={}, tools={}",
            CODE_EXHAUSTED, agentName(agent), session, event.getMaxIters(), state.callCounts.keySet());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("agent", agentName(agent));
        fields.put("session", session);
        fields.put("maxIters", event.getMaxIters());
        fields.put("currentIter", event.getCurrentIter());
        fields.put("distinctToolCalls", state.callCounts.size());
        audit(AUDIT_EXHAUSTED, fields);
        handoff(session, event.getMaxIters());
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
        return Flux.just(
            new TextBlockDeltaEvent(blockEnd.getReplyId(), blockEnd.getBlockId(), exhaustedNotice), blockEnd);
    }

    /**
     * 只读最终结果的消费方（{@code call()}：IM 渠道、评测、多专家）看到的说明：追加到收尾回复上。
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
        AgentResultEvent rewritten = new AgentResultEvent(withNotice(result.getResult()));
        if (state.noticeStreamed || !state.textStreamed) {
            return Flux.just(rewritten);
        }
        state.noticeStreamed = true;
        return noticeBlock(state).concatWith(Flux.just(rewritten));
    }

    /**
     * 收尾没有正常结束的文本块时补发说明。
     *
     * <p>收尾模型中途失败时框架已经发出块开始与部分正文，却再也不会发块结束——说明补进这个悬空的块并替它结束，
     * 另起新块会让它永远不结束。收尾一个字都没吐时才单独起一个完整的块。</p>
     */
    private Flux<AgentEvent> noticeBlock(TurnState state) {
        TextBlockStartEvent dangling = state.openTextBlock;
        if (dangling != null) {
            state.openTextBlock = null;
            return Flux.just(
                new TextBlockDeltaEvent(dangling.getReplyId(), dangling.getBlockId(), exhaustedNotice),
                new TextBlockEndEvent(dangling.getReplyId(), dangling.getBlockId()));
        }
        String replyId = UUID.randomUUID().toString().replace("-", "");
        return Flux.just(
            new TextBlockStartEvent(replyId, NOTICE_BLOCK_ID),
            new TextBlockDeltaEvent(replyId, NOTICE_BLOCK_ID, exhaustedNotice),
            new TextBlockEndEvent(replyId, NOTICE_BLOCK_ID));
    }

    /** 说明接在最后一个文本块之后；消息身份、元数据与其余内容块原样保留。 */
    private Msg withNotice(Msg msg) {
        if (msg == null) {
            return Msg.builder().role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(exhaustedNotice).build()).build();
        }
        List<ContentBlock> content = new ArrayList<>(msg.getContent());
        for (int i = content.size() - 1; i >= 0; i--) {
            if (content.get(i) instanceof TextBlock last) {
                content.set(i, TextBlock.builder().text(last.getText() + exhaustedNotice).build());
                return msg.withContent(content);
            }
        }
        content.add(TextBlock.builder().text(exhaustedNotice).build());
        return msg.withContent(content);
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
            handoffService.create(sessionId, "自动应答轮次用尽（maxIters=" + maxIters + "），需人工接手");
        } catch (Exception e) {
            log.error("handoff on iterations exhausted failed, code={}, session={}",
                CODE_EXHAUSTED, sessionId, e);
        }
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

    /** 一轮对话的观测状态。 */
    private static final class TurnState {
        private final Map<String, String> toolNames = new HashMap<>();
        private final Map<String, StringBuilder> arguments = new HashMap<>();
        private final Map<String, Integer> callCounts = new HashMap<>();
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
