package com.example.customerwork.agent;

import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 可观测 Hook（对应「Hook / 可观测性」专题，数据飞轮采集入口）。
 *
 * <p>在 Agent 生命周期关键节点打点：推理开始、工具调用前后、单次请求结束（含 token 用量）、异常。
 * 除日志外，可选接入 Micrometer {@link MeterRegistry}，把请求数、工具调用数、错误数、token 总量
 * 暴露为指标，经 Actuator / Prometheus 供监控与数据飞轮消费。</p>
 *
 * <p>Hook 必须只读透传事件且不抛异常，因此整体包了兜底。</p>
 * @author owlzhangfq@gmail.com
 */
public class ObservabilityHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityHook.class);

    private static final String M_REQUESTS = "customerwork.agent.requests";
    private static final String M_REASONING = "customerwork.agent.reasoning";
    private static final String M_TOOL_CALLS = "customerwork.agent.tool.calls";
    private static final String M_ERRORS = "customerwork.agent.errors";
    private static final String M_TOKENS = "customerwork.agent.tokens.total";

    /** 可为 null：未接入 Micrometer 时降级为仅日志。 */
    private final MeterRegistry meterRegistry;

    public ObservabilityHook() {
        this(null);
    }

    public ObservabilityHook(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            record(event);
        } catch (Exception e) {
            log.warn("[OTEL] 观测打点异常（已忽略）: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    private void record(HookEvent event) {
        if (event instanceof PreReasoningEvent e) {
            log.info("[OTEL] 推理开始 agent={} model={}", e.getAgent().getName(), e.getModelName());
            count(M_REASONING);
        } else if (event instanceof PreActingEvent e) {
            String tool = e.getToolUse().getName();
            log.info("[OTEL] 工具调用开始 tool={} args={}", tool, e.getToolUse().getInput());
            countTool(tool);
        } else if (event instanceof PostActingEvent e) {
            log.info("[OTEL] 工具调用结束 tool={} stopRequested={}",
                e.getToolUse().getName(), e.isStopRequested());
        } else if (event instanceof PostCallEvent e) {
            count(M_REQUESTS);
            logUsage(e.getFinalMessage());
        } else if (event instanceof ErrorEvent e) {
            log.error("[OTEL] Agent 执行异常 agent={}", e.getAgent().getName(), e.getError());
            count(M_ERRORS);
        }
    }

    private void logUsage(Msg finalMessage) {
        if (finalMessage == null) {
            return;
        }
        ChatUsage usage = finalMessage.getChatUsage();
        if (usage != null) {
            log.info("[OTEL] 请求结束 inputTokens={} outputTokens={} totalTokens={} time={}s",
                usage.getInputTokens(), usage.getOutputTokens(),
                usage.getTotalTokens(), usage.getTime());
            if (meterRegistry != null) {
                meterRegistry.counter(M_TOKENS).increment(usage.getTotalTokens());
            }
        } else {
            log.info("[OTEL] 请求结束（无 token 用量信息）");
        }
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

    @Override
    public int priority() {
        return 800;
    }
}
