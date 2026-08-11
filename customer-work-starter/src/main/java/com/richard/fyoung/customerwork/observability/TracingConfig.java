package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.tracing.TracerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 原生链路追踪装配（对应「可观测 · Tracing」）。
 *
 * <p>当 {@code customer-work.observability.tracing-enabled=true} 时，注册 {@link LoggingTracer}
 * 到框架 {@code TracerRegistry} 并开启 tracing Hook，使每次模型 / 工具 / Agent 调用自动打 span。</p>
 *
 * <p><b>与 OTel 路径的互斥</b>：{@code observability.otel.enabled=true} 时改由 {@link OtelTracingConfig}
 * 装配的 {@code OtelTracingMiddleware} 采集同样的三段 span 并经 OTLP 导出，此时再注册 LoggingTracer
 * 只会把同一批 span 重复打进日志、徒增噪声与开销，故本类让位不注册（{@code tracing-enabled} 的语义不变，
 * 仍是"是否启用框架原生 Tracer"，只是 OTel 开启时该路径由 OTel 承担）。</p>
 *
 * <p>Studio 可视化（{@code StudioManager} + WebSocket）与 Training（RM Gallery / Trinity-RFT）
 * 需要外部 Studio 服务与训练平台，属基础设施扩展点，按 {@code observability.studio} 配置后另行接入。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    private final CustomerWorkProperties properties;

    public TracingConfig(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void register() {
        if (!properties.getObservability().isTracingEnabled()) {
            return;
        }
        // OTel 已接管：跳过 LoggingTracer，避免同一批 span 出两份（日志一份 + OTLP 一份）
        if (properties.getObservability().getOtel().isEnabled()) {
            log.info("[TRACE] LoggingTracer skipped, spans are collected by OTel SDK instead");
            return;
        }
        TracerRegistry.register(new LoggingTracer());
        TracerRegistry.enableTracingHook();
        log.info("[TRACE] 原生链路追踪已启用（LoggingTracer）");
    }
}
