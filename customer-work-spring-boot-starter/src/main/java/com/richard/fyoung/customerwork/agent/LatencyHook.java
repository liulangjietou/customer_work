package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 延迟埋点 Hook（对应「可观测性 · 延迟 / 成本 SLO」）。
 *
 * <p>把一次请求的耗时拆成四段并接 Micrometer Timer（带 P50/P95/P99 分位），便于用真实流量
 * 而非经验值评估延迟：</p>
 * <ul>
 *   <li>{@code customerwork.agent.latency.e2e}：端到端（PreCall → PostCall）；</li>
 *   <li>{@code customerwork.agent.latency.reasoning}：单轮推理（PreReasoning → PostReasoning）；</li>
 *   <li>{@code customerwork.agent.latency.tool}：单个工具（PreActing → PostActing，按 tool 维度打标）；</li>
 *   <li>{@code customerwork.agent.latency.ttft}：首字时间（PreCall → 首个推理增量分片）。</li>
 * </ul>
 *
 * <p>计时状态按 Agent 名（每会话唯一）与工具调用 ID 为 key 暂存；同一会话的请求由
 * {@code SessionStateManager} 串行化，跨会话天然隔离，故无竞态。未接入 Micrometer 时降级为 DEBUG 日志。
 * Hook 必须只读透传、绝不打断主链路，因此整体兜底。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class LatencyHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(LatencyHook.class);

    private static final String M_E2E = "customerwork.agent.latency.e2e";
    private static final String M_REASONING = "customerwork.agent.latency.reasoning";
    private static final String M_TOOL = "customerwork.agent.latency.tool";
    private static final String M_TTFT = "customerwork.agent.latency.ttft";

    private final boolean enabled;
    /** 可为 null：未接入 Micrometer 时仅日志。 */
    private final MeterRegistry meterRegistry;

    /** key -> 起始纳秒时间戳（call:/reason:/tool: 前缀）。 */
    private final ConcurrentMap<String, Long> startNanos = new ConcurrentHashMap<>();
    /** 本次请求是否已记录过 TTFT（避免重复记录首字）。 */
    private final java.util.Set<String> ttftRecorded = ConcurrentHashMap.newKeySet();

    public LatencyHook(CustomerWorkProperties properties, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.enabled = properties.getHooks().getLatency().isEnabled();
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (enabled) {
            try {
                handle(event);
            } catch (Exception e) {
                log.debug("[LAT] 延迟埋点异常（已忽略）: {}", e.getMessage());
            }
        }
        return Mono.just(event);
    }

    private void handle(HookEvent event) {
        long now = System.nanoTime();
        String agent = agentName(event);

        if (event instanceof PreCallEvent) {
            startNanos.put("call:" + agent, now);
            ttftRecorded.remove(agent);
        } else if (event instanceof PreReasoningEvent) {
            startNanos.put("reason:" + agent, now);
        } else if (event instanceof PostReasoningEvent) {
            record(M_REASONING, null, since("reason:" + agent, now));
        } else if (event instanceof ReasoningChunkEvent) {
            // 首个推理增量分片视为"首字"，每次请求只记一次
            if (ttftRecorded.add(agent)) {
                Long callStart = startNanos.get("call:" + agent);
                if (callStart != null) {
                    record(M_TTFT, null, now - callStart);
                }
            }
        } else if (event instanceof PreActingEvent e) {
            startNanos.put("tool:" + toolKey(e.getToolUse().getId(), agent), now);
        } else if (event instanceof PostActingEvent e) {
            String tool = e.getToolUse() == null ? "unknown" : e.getToolUse().getName();
            record(M_TOOL, tool, since("tool:" + toolKey(e.getToolUse() == null ? null : e.getToolUse().getId(), agent), now));
        } else if (event instanceof PostCallEvent) {
            record(M_E2E, null, since("call:" + agent, now));
            ttftRecorded.remove(agent);
        }
    }

    /** 取出起始时间并清理；返回耗时纳秒，未配对则返回 -1。 */
    private long since(String key, long now) {
        Long start = startNanos.remove(key);
        return start == null ? -1 : now - start;
    }

    private void record(String metric, String toolTag, long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        Duration d = Duration.ofNanos(durationNanos);
        if (meterRegistry != null) {
            timer(metric, toolTag).record(d);
        }
        if (log.isDebugEnabled()) {
            log.debug("[LAT] {}{} = {} ms", metric,
                toolTag == null ? "" : "(" + toolTag + ")", d.toMillis());
        }
    }

    private Timer timer(String metric, String toolTag) {
        Timer.Builder b = Timer.builder(metric)
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram();
        if (toolTag != null) {
            b.tag("tool", toolTag);
        }
        return b.register(meterRegistry);
    }

    private String toolKey(String toolUseId, String agent) {
        return (toolUseId == null || toolUseId.isBlank()) ? agent : toolUseId;
    }

    private String agentName(HookEvent event) {
        try {
            return event.getAgent() == null ? "unknown" : event.getAgent().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public int priority() {
        // 尽早运行，使计时尽量贴近真实边界
        return 50;
    }
}
