package com.richard.fyoung.customerwork.middleware;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Function;

/**
 * 延迟埋点中间件（2.0 Middleware，承接 1.x LatencyHook，「可观测性 · 延迟 / 成本 SLO」）。
 *
 * <p>用中间件天然的"包裹"语义采集分段耗时（带 P50/P95/P99 分位）：</p>
 * <ul>
 *   <li>{@code customerwork.agent.latency.e2e}：端到端（{@link #onAgent} 整次调用）；</li>
 *   <li>{@code customerwork.agent.latency.reasoning}：单轮推理（{@link #onReasoning}）；</li>
 *   <li>{@code customerwork.agent.latency.tool}：工具执行（{@link #onActing}）。</li>
 * </ul>
 *
 * <p>未接入 Micrometer 时降级为 DEBUG 日志。中间件只读透传、不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class LatencyMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(LatencyMiddleware.class);

    private static final String M_E2E = "customerwork.agent.latency.e2e";
    private static final String M_REASONING = "customerwork.agent.latency.reasoning";
    private static final String M_TOOL = "customerwork.agent.latency.tool";
    private static final String M_SLOW_REQUESTS = "customerwork.agent.slow.requests";

    private final boolean enabled;
    /** 慢请求留证阈值（毫秒）；<=0 关闭留证。 */
    private final long slowThresholdMs;
    /** 可为 null：未接入 Micrometer 时仅日志。 */
    private final MeterRegistry meterRegistry;

    public LatencyMiddleware(CustomerWorkProperties properties,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.enabled = properties.getHooks().getLatency().isEnabled();
        this.slowThresholdMs = properties.getHooks().getLatency().getSlowRequestThresholdMs();
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Flux<AgentEvent> timed = timed(M_E2E, next.apply(input));
        if (!enabled) {
            return timed;
        }
        // 慢请求/异常请求留证：端到端超阈值或出错时输出结构化现场（借 MDC 自动带 requestId/sessionId）。
        // 用 doOnEach 在终止信号「向下游传播前」完成留证，避免像 doFinally 那样在下游收到终止后才异步执行。
        long[] start = new long[1];
        return timed
            .doOnSubscribe(s -> start[0] = System.nanoTime())
            .doOnEach(signal -> {
                if (signal.isOnComplete() || signal.isOnError()) {
                    captureIfSlowOrError(agent, System.nanoTime() - start[0], signal.getThrowable());
                }
            });
    }

    /** 端到端超阈值或出错时留证：结构化日志 + 计数指标，指引运维用诊断 API 拉全景。 */
    private void captureIfSlowOrError(Agent agent, long durationNanos, Throwable error) {
        long ms = Duration.ofNanos(Math.max(0, durationNanos)).toMillis();
        boolean slow = slowThresholdMs > 0 && ms >= slowThresholdMs;
        if (!slow && error == null) {
            return;
        }
        String agentName = agent == null ? "?" : agent.getName();
        String outcome = error != null ? "ERROR" : "SLOW";
        if (meterRegistry != null) {
            Counter.builder(M_SLOW_REQUESTS).tag("outcome", outcome).register(meterRegistry).increment();
        }
        log.error("[SLOWREQ] outcome={} agent={} latencyMs={} thresholdMs={} code={} error={}",
            outcome, agentName, ms, slowThresholdMs, "SLOW_OR_ERROR_REQUEST",
            error == null ? "-" : error.getMessage());
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return timed(M_REASONING, next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return timed(M_TOOL, next.apply(input));
    }

    /** 包裹一段事件流，完成/出错时记录从订阅到终止的耗时。 */
    private Flux<AgentEvent> timed(String metric, Flux<AgentEvent> upstream) {
        if (!enabled) {
            return upstream;
        }
        long[] start = new long[1];
        return upstream
            .doOnSubscribe(s -> start[0] = System.nanoTime())
            .doFinally(sig -> record(metric, System.nanoTime() - start[0]));
    }

    private void record(String metric, long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        Duration d = Duration.ofNanos(durationNanos);
        if (meterRegistry != null) {
            Timer.builder(metric)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(d);
        }
        if (log.isDebugEnabled()) {
            log.debug("[LAT] {} = {} ms", metric, d.toMillis());
        }
    }
}
