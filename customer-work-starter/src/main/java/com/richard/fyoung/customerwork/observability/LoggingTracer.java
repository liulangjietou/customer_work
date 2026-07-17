package com.richard.fyoung.customerwork.observability;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Supplier;

/**
 * 轻量链路追踪 Tracer（对应「可观测 · 原生 Tracing」）。
 *
 * <p>实现框架 {@link Tracer}，在 Agent / 工具调用前后打 span 边界日志（带耗时），并透传原调用结果。
 * 注册到 {@code TracerRegistry} 后由框架在每次调用时回调——这是接入 OpenTelemetry 之前的最小可用实现，
 * 生产可替换为 {@code TelemetryTracer}（包一个 OTel Tracer）把 span 导出到 Jaeger/Tempo。</p>
 * @author owlzhangfq@gmail.com
 */
public class LoggingTracer implements Tracer {

    private static final Logger log = LoggerFactory.getLogger(LoggingTracer.class);

    @Override
    public Mono<Msg> callAgent(AgentBase agent, List<Msg> input, Supplier<Mono<Msg>> action) {
        String name = agent == null ? "agent" : agent.getName();
        long start = System.currentTimeMillis();
        return action.get()
            .doFirst(() -> log.info("[TRACE] span start agent={}", name))
            .doFinally(sig -> log.info("[TRACE] span end agent={} cost={}ms signal={}",
                name, System.currentTimeMillis() - start, sig));
    }

    @Override
    public Mono<ToolResultBlock> callTool(Toolkit toolkit, ToolCallParam param,
                                          Supplier<Mono<ToolResultBlock>> action) {
        long start = System.currentTimeMillis();
        return action.get()
            .doFirst(() -> log.info("[TRACE] span start tool-call"))
            .doFinally(sig -> log.info("[TRACE] span end tool-call cost={}ms signal={}",
                System.currentTimeMillis() - start, sig));
    }
}
