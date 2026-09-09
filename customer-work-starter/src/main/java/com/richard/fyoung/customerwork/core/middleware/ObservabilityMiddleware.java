package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.ToolUseBlock;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 可观测中间件（2.0 Middleware，承接 1.x ObservabilityHook，数据飞轮采集入口）。
 *
 * <p>跨三段打点：{@link #onAgent}（请求数 / 错误数，包裹整次调用）、{@link #onReasoning}（推理段）、
 * {@link #onActing}（工具调用数，按 tool 维度）。除日志外，可选接入 Micrometer 暴露为指标。
 * 中间件只读透传、不改变事件，异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
public class ObservabilityMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityMiddleware.class);

    private static final String M_REQUESTS = "customerwork.agent.requests";
    private static final String M_REASONING = "customerwork.agent.reasoning";
    private static final String M_TOOL_CALLS = "customerwork.agent.tool.calls";
    private static final String M_ERRORS = "customerwork.agent.errors";

    /** 可为 null：未接入 Micrometer 时降级为仅日志。 */
    private final MeterRegistry meterRegistry;

    public ObservabilityMiddleware() {
        this(null);
    }

    public ObservabilityMiddleware(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        count(M_REQUESTS);
        return next.apply(input)
            .doOnError(e -> {
                log.error("[OTEL] agent invocation error, code={}", "AGENT_ERROR", e);
                count(M_ERRORS);
            });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        log.info("[OTEL] reasoning start, agent={} tools={}",
            agent == null ? "?" : agent.getName(),
            input.tools() == null ? 0 : input.tools().size());
        count(M_REASONING);
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (input.toolCalls() != null) {
            for (ToolUseBlock use : input.toolCalls()) {
                log.info("[OTEL] tool call start, tool={}", use.getName());
                countTool(use.getName());
            }
        }
        return next.apply(input);
    }

    private void count(String metric) {
        if (meterRegistry != null) {
            meterRegistry.counter(metric).increment();
        }
    }

    private void countTool(String toolName) {
        if (meterRegistry != null) {
            meterRegistry.counter(M_TOOL_CALLS, "tool", toolName == null ? "unknown" : toolName)
                .increment();
        }
    }

    /** 顺序契约见 {@link MiddlewareOrders}：打点需覆盖内层全链路。 */
    @Override
    public int order() {
        return MiddlewareOrders.OBSERVABILITY;
    }
}
