package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.tracing.TracerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 原生链路追踪装配（对应「可观测 · Tracing」）。
 *
 * <p>当 {@code customer-work.observability.tracing-enabled=true} 时，注册 {@link LoggingTracer}
 * 到框架 {@code TracerRegistry} 并开启 tracing Hook，使每次模型 / 工具 / Agent 调用自动打 span。
 * 生产可把注册对象换成 OTel 的 {@code TelemetryTracer} 以导出到 Jaeger/Tempo。</p>
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
        TracerRegistry.register(new LoggingTracer());
        TracerRegistry.enableTracingHook();
        log.info("[TRACE] 原生链路追踪已启用（LoggingTracer）");
    }
}
