package com.richard.fyoung.customerwork.calllog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.MdcContextLifter;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 智能体调用分段耗时采集中间件（2.0 Middleware，「智能体耗时统计报表」通用采集入口）。
 *
 * <p>用中间件"包裹"语义按请求采集分段耗时：</p>
 * <ul>
 *   <li>{@link #onAgent}：请求级——创建 {@link AgentCallCollector} 放进 {@link RuntimeContext}，
 *       从事件流的 {@link AgentResultEvent} 采集最终回答，流终止时组装 {@link AgentCallRecord} 交给 Sink；</li>
 *   <li>{@link #onModelCall}：每次大模型 API 调用计一段（MODEL，段名取模型名）；</li>
 *   <li>{@link #onActing}：每次工具执行计一段，按 {@link ToolKindRegistry} 归为 TOOL/MCP/SKILL，
 *       成败取自流中的 {@link ToolResultEndEvent}（异常走 onError）。</li>
 * </ul>
 *
 * <p><b>答案采集方式</b>：直接从 onAgent 事件流的 {@code AGENT_RESULT} 事件（{@link AgentResultEvent}）
 * 取完整最终消息文本——框架在流末尾回放一个带完整 {@code Msg} 的 AGENT_RESULT，无需自行拼装增量分片，
 * 可靠。故答案在中间件内采集，无需调用方回写（{@link AgentCallCollector#setAnswer} 仍开放供特殊场景覆盖）。</p>
 *
 * <p><b>异常安全</b>（防御式编程唯一收敛处）：采集/组装/下沉的任何异常都在本类内 catch 吞掉并记 error 日志，
 * 只读透传事件、绝不打断主链路（参考 LatencyMiddleware 的只读透传原则）。开关 {@code customer-work.call-log.enabled}
 * 关闭时全部 hook 直通。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentCallTimingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentCallTimingMiddleware.class);

    private static final String UNKNOWN = "unknown";
    private static final String CANCELLED = "cancelled";

    private final boolean enabled;
    private final ToolKindRegistry toolKindRegistry;
    private final AgentCallRecordSink sink;

    public AgentCallTimingMiddleware(CustomerWorkProperties properties,
                                     ToolKindRegistry toolKindRegistry,
                                     AgentCallRecordSink sink) {
        this.enabled = properties.getCallLog().isEnabled();
        this.toolKindRegistry = toolKindRegistry;
        this.sink = sink;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        // ctx 归一（关键坑，见方法级 Javadoc）：onAgent 的 ctx 入参在框架废弃的 stream(msgs,options,ctx)
        // 路径上恒为 null——框架只把调用方 ctx 挂到 Reactor Context 的 RUNTIME_CONTEXT_KEY 上，而
        // onModelCall/onActing 用的却是从该 key 解析出的真实 rc。故这里也从 Reactor Context 兜底取同一实例，
        // 保证三个 hook 读写同一个 ctx（collector 互通、meta 可读、sessionId 非空）。deferContextual 在订阅期
        // 拿到承载 RUNTIME_CONTEXT_KEY 的上下文视图。
        return Flux.deferContextual(cv -> onAgentWithContext(agent, resolveEffectiveCtx(ctx, cv), input, next));
    }

    /** 用归一后的 {@code ctx} 执行采集主逻辑（供 {@link #onAgent} 在 deferContextual 内调用）。 */
    private Flux<AgentEvent> onAgentWithContext(Agent agent, RuntimeContext ctx, AgentInput input,
                                                Function<AgentInput, Flux<AgentEvent>> next) {
        // 嵌套调用防护：子 agent 若复用同一 RuntimeContext（同链路再次进入 onAgent），不覆盖父采集器、
        // 不另发记录——子 agent 的模型/工具分段经 ctx 里既有采集器自然归入父请求，父级统一结算
        if (collectorOf(ctx) != null) {
            return next.apply(input);
        }
        AgentCallCollector collector = new AgentCallCollector();
        // 放进 ctx 供 onModelCall/onActing 追加分段；ctx 缺失（如单测直传 null 且无 Reactor 上下文）
        // 则仅本 hook 记录，不报错
        putCollector(ctx, collector);

        AgentCallMeta meta = safeMeta(ctx);
        String question = resolveQuestion(meta, input);

        AtomicBoolean ended = new AtomicBoolean(false);
        return next.apply(input)
            .doOnNext(event -> captureAnswer(collector, event))
            .doOnComplete(() -> settleRecord(ended, collector, agent, ctx, meta, question, true, null))
            .doOnError(e -> settleRecord(ended, collector, agent, ctx, meta, question, false, safeMsg(e)))
            .doOnCancel(() -> settleRecord(ended, collector, agent, ctx, meta, question, false, CANCELLED));
    }

    /**
     * ctx 归一：优先用框架直接传入的 ctx 入参（call/streamEvents 路径非空）；入参为 null 时（废弃
     * stream(msgs,options,ctx) 路径）从 Reactor Context 的 {@link AgentBase#RUNTIME_CONTEXT_KEY} 兜底取，
     * 这正是 onModelCall/onActing 拿到的同一个调用方 ctx 实例，从而三个 hook 共享同一 ctx。取不到则返回
     * null（本 hook 仍可独立记录，只是无 meta/分段互通）。
     */
    private RuntimeContext resolveEffectiveCtx(RuntimeContext paramCtx, reactor.util.context.ContextView cv) {
        if (paramCtx != null) {
            return paramCtx;
        }
        try {
            Object v = cv.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
            return v instanceof RuntimeContext rc ? rc : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        AgentCallCollector collector = collectorOf(ctx);
        if (collector == null) {
            return next.apply(input);
        }
        String modelName = input.model() != null && input.model().getModelName() != null
            ? input.model().getModelName() : UNKNOWN;
        return timeSegment(next.apply(input), collector, AgentCallKind.MODEL, modelName, null);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        AgentCallCollector collector = collectorOf(ctx);
        if (collector == null) {
            return next.apply(input);
        }
        String toolName = firstToolName(input);
        AgentCallKind kind = toolKindRegistry.classify(toolName);
        // 工具执行成败取自流中的 ToolResultEndEvent（onError 兜底捕获异常）
        AtomicReference<ToolResultState> lastState = new AtomicReference<>(null);
        Flux<AgentEvent> upstream = next.apply(input)
            .doOnNext(event -> {
                if (event instanceof ToolResultEndEvent tre && tre.getState() != null) {
                    lastState.set(tre.getState());
                }
            });
        return timeSegment(upstream, collector, kind, toolName, lastState);
    }

    /**
     * 通用分段计时：doOnSubscribe 记开始，三态终止（complete/error/cancel）各结算一次。
     * {@code stateRef} 非空时（onActing）complete 分支据 {@link ToolResultState} 判成败，否则视为成功。
     */
    private Flux<AgentEvent> timeSegment(Flux<AgentEvent> upstream, AgentCallCollector collector,
                                         AgentCallKind kind, String name,
                                         AtomicReference<ToolResultState> stateRef) {
        long[] startMs = new long[1];
        long[] startNano = new long[1];
        AtomicBoolean ended = new AtomicBoolean(false);
        return upstream
            .doOnSubscribe(s -> {
                startMs[0] = System.currentTimeMillis();
                startNano[0] = System.nanoTime();
            })
            .doOnComplete(() -> {
                if (ended.compareAndSet(false, true)) {
                    boolean success = isSuccess(stateRef);
                    String err = success ? null : "tool result state=" + (stateRef == null ? null : stateRef.get());
                    addSegment(collector, kind, name, startMs[0], startNano[0], success, err);
                }
            })
            .doOnError(e -> {
                if (ended.compareAndSet(false, true)) {
                    addSegment(collector, kind, name, startMs[0], startNano[0], false, safeMsg(e));
                }
            })
            .doOnCancel(() -> {
                if (ended.compareAndSet(false, true)) {
                    addSegment(collector, kind, name, startMs[0], startNano[0], false, CANCELLED);
                }
            });
    }

    /** onActing 无 stateRef（模型段）或状态为 SUCCESS/RUNNING/空 视为成功；ERROR/DENIED/INTERRUPTED 视为失败。 */
    private boolean isSuccess(AtomicReference<ToolResultState> stateRef) {
        if (stateRef == null) {
            return true;
        }
        ToolResultState state = stateRef.get();
        return state == null || state == ToolResultState.SUCCESS || state == ToolResultState.RUNNING;
    }

    /** 追加分段（异常安全：采集失败不影响主链路）。 */
    private void addSegment(AgentCallCollector collector, AgentCallKind kind, String name,
                            long startMs, long startNano, boolean success, String errorMsg) {
        try {
            collector.addSegment(kind, name, startMs, startNano, success, errorMsg);
        } catch (Exception e) {
            log.error("agent call segment collect failed, code={}, kind={}, name={}",
                "CALLLOG-SEGMENT-FAIL", kind, name, e);
        }
    }

    /** 从 AGENT_RESULT 事件采集最终回答（异常安全）。 */
    private void captureAnswer(AgentCallCollector collector, AgentEvent event) {
        try {
            if (event instanceof AgentResultEvent are && are.getResult() != null) {
                String text = are.getResult().getTextContent();
                if (text != null && !text.isEmpty()) {
                    collector.setAnswer(text);
                }
            }
        } catch (Exception e) {
            log.error("agent call answer collect failed, code={}", "CALLLOG-ANSWER-FAIL", e);
        }
    }

    /** 组装记录并交 Sink（异常安全：只结算一次，任何异常吞掉不打断主链路）。 */
    private void settleRecord(AtomicBoolean ended, AgentCallCollector collector, Agent agent,
                              RuntimeContext ctx, AgentCallMeta meta, String question,
                              boolean success, String errorMsg) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        try {
            String agentName = agent == null ? null : agent.getName();
            String requestId = firstNonBlank(meta == null ? null : meta.requestId(), mdcRequestId(), UNKNOWN);
            String userId = ctx == null ? null : ctx.getUserId();
            String username = firstNonBlank(meta == null ? null : meta.username(), userId, UNKNOWN);
            String agentCode = firstNonBlank(meta == null ? null : meta.agentCode(), agentName, UNKNOWN);
            String resolvedAgentName = firstNonBlank(meta == null ? null : meta.agentName(), agentName, UNKNOWN);
            String sessionId = ctx == null ? null : ctx.getSessionId();
            AgentCallSessionType sessionType = meta != null && meta.sessionType() != null
                ? meta.sessionType() : AgentCallSessionType.CHAT;

            AgentCallRecord record = collector.toRecord(requestId, userId, username, agentCode,
                resolvedAgentName, sessionId, sessionType, question, System.currentTimeMillis(),
                success, errorMsg);
            sink.emit(record);
        } catch (Exception e) {
            log.error("agent call record settle failed, code={}", "CALLLOG-SETTLE-FAIL", e);
        }
    }

    /** onAgent 入参首条用户消息文本（meta.question 缺失时的兜底问题来源）。 */
    private String resolveQuestion(AgentCallMeta meta, AgentInput input) {
        if (meta != null && meta.question() != null && !meta.question().isEmpty()) {
            return meta.question();
        }
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return null;
        }
        Msg first = input.msgs().get(0);
        return first == null ? null : first.getTextContent();
    }

    private String firstToolName(ActingInput input) {
        List<ToolUseBlock> calls = input == null ? null : input.toolCalls();
        if (calls == null || calls.isEmpty() || calls.get(0) == null) {
            return UNKNOWN;
        }
        String name = calls.get(0).getName();
        return name == null || name.isBlank() ? UNKNOWN : name;
    }

    private void putCollector(RuntimeContext ctx, AgentCallCollector collector) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.put(AgentCallCollector.class, collector);
        } catch (Exception e) {
            log.error("agent call collector bind failed, code={}", "CALLLOG-BIND-FAIL", e);
        }
    }

    private AgentCallCollector collectorOf(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.get(AgentCallCollector.class);
        } catch (Exception e) {
            return null;
        }
    }

    private AgentCallMeta safeMeta(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.get(AgentCallMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String mdcRequestId() {
        try {
            return org.slf4j.MDC.get(MdcContextLifter.REQUEST_ID_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeMsg(Throwable e) {
        return e == null ? null : e.getMessage();
    }

    private static String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return c;
    }
}
