package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.richard.fyoung.customerwork.infra.config.properties.ObservabilityProperties;

/**
 * OTel SDK 装配单测（离线，不连接真实 Collector：BatchSpanProcessor 异步导出失败不影响装配）。
 *
 * <p>覆盖：①配置默认值 ②默认关闭时整组 Bean 不装配且不污染 {@code GlobalOpenTelemetry}
 * ③开启时 SDK 与 {@link OtelTracingMiddleware} 都在容器里（后者由 Agent 工厂的
 * {@code ObjectProvider<MiddlewareBase>} 自动吸收）。</p>
 *
 * <p>{@code GlobalOpenTelemetry} 是 JVM 级单例、只能 set 一次，每个用例前后都用
 * {@code resetForTest()} 清干净，避免用例间互相污染（也避免污染同 JVM 的其它测试类）。</p>
 * @author owlzhangfq@gmail.com
 */
class OtelTracingConfigTest {

    @BeforeEach
    @AfterEach
    void resetGlobalOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void otelProperties_shouldHaveSaneDefaults() {
        ObservabilityProperties.Otel cfg =
            new CustomerWorkProperties().getObservability().getOtel();
        assertFalse(cfg.isEnabled(), "默认不启用 OTel SDK");
        assertEquals("http://localhost:4317", cfg.getEndpoint());
        assertEquals("customer-work", cfg.getServiceName());
        assertEquals(1.0d, cfg.getSamplerRatio(), 0.0001d);
    }

    @Test
    void context_shouldNotWireAnything_whenOtelDisabledByDefault() {
        runner().run(context -> {
            assertEquals(0, context.getBeanNamesForType(OtelTracingMiddleware.class).length,
                "默认关闭时不装配 OTel 追踪中间件");
            assertEquals(0, context.getBeanNamesForType(OpenTelemetrySdk.class).length,
                "默认关闭时不构建 SDK");
            // 未被污染：全局拿到的是 no-op 实现，产出的 span 上下文无效
            assertFalse(GlobalOpenTelemetry.getTracer("probe").spanBuilder("probe")
                    .startSpan().getSpanContext().isValid(),
                "关闭时不得注册全局 SDK，GlobalOpenTelemetry 仍是 no-op");
        });
    }

    @Test
    void context_shouldNotWireAnything_whenOtelExplicitlyDisabled() {
        runner().withPropertyValues("customer-work.observability.otel.enabled=false")
            .run(context -> assertEquals(0,
                context.getBeanNamesForType(OtelTracingMiddleware.class).length));
    }

    @Test
    void context_shouldWireSdkAndMiddleware_whenOtelEnabled() {
        runner().withPropertyValues(
                "customer-work.observability.otel.enabled=true",
                "customer-work.observability.otel.service-name=customer-work-test",
                "customer-work.observability.otel.sampler-ratio=0.5")
            .run(context -> {
                assertEquals(1, context.getBeanNamesForType(OpenTelemetrySdk.class).length,
                    "开启时构建 SDK");
                assertEquals(1, context.getBeanNamesForType(OtelTracingMiddleware.class).length,
                    "开启时装配框架 OTel 追踪中间件，由 Agent 工厂自动挂链");
                // 全局已注册真实 SDK：产出的 span 上下文有效（no-op 实现产出的恒无效）
                assertTrue(GlobalOpenTelemetry.getTracer("probe").spanBuilder("probe")
                        .startSpan().getSpanContext().isValid(),
                    "开启时全局 SDK 已注册");
            });
    }

    /** 只装 OTel 装配类 + 属性绑定，不拉起整个 starter。 */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, OtelTracingConfig.class);
    }

    @EnableConfigurationProperties(CustomerWorkProperties.class)
    static class PropertiesConfig {
    }
}
