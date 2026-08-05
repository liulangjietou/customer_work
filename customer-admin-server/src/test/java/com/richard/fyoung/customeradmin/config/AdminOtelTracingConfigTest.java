package com.richard.fyoung.customeradmin.config;

import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminOtelTracingConfig} 装配单测（离线，不连真实 Collector：BatchSpanProcessor 异步导出，
 * 后端不可达不影响装配）。
 *
 * <p>覆盖：①默认关闭时整组 Bean 不装配、不污染 {@code GlobalOpenTelemetry} ②显式关闭同上
 * ③开启时 SDK 与 {@link OtelTracingMiddleware} 都在容器里且全局 SDK 已注册
 * ④开启时 {@code AdminAgentInstanceFactory} 的注入口径（{@code ObjectProvider<OtelTracingMiddleware>}）
 * 确实能拿到中间件——admin 的工厂是显式挂链，拿不到就等于没接上。</p>
 *
 * <p>{@code GlobalOpenTelemetry} 是 JVM 级单例、只能 set 一次，每个用例前后都 {@code resetForTest()}
 * 清干净，避免用例之间以及对同 JVM 其它测试类的污染。</p>
 * @author owlzhangfq@gmail.com
 */
class AdminOtelTracingConfigTest {

    private static final String ENABLED_KEY = "admin.observability.otel.enabled=true";

    @BeforeEach
    @AfterEach
    void resetGlobalOtel() {
        GlobalOpenTelemetry.resetForTest();
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
        runner().withPropertyValues("admin.observability.otel.enabled=false")
            .run(context -> assertEquals(0,
                context.getBeanNamesForType(OtelTracingMiddleware.class).length,
                "显式关闭时不装配 OTel 追踪中间件"));
    }

    @Test
    void context_shouldWireSdkAndMiddleware_whenOtelEnabled() {
        runner().withPropertyValues(ENABLED_KEY,
                "admin.observability.otel.service-name=customer-admin-test",
                "admin.observability.otel.sampler-ratio=0.5")
            .run(context -> {
                assertEquals(1, context.getBeanNamesForType(OpenTelemetrySdk.class).length,
                    "开启时构建 SDK");
                assertEquals(1, context.getBeanNamesForType(OtelTracingMiddleware.class).length,
                    "开启时装配框架 OTel 追踪中间件");
                // 全局已注册真实 SDK：产出的 span 上下文有效（no-op 实现产出的恒无效）
                assertTrue(GlobalOpenTelemetry.getTracer("probe").spanBuilder("probe")
                        .startSpan().getSpanContext().isValid(),
                    "开启时全局 SDK 已注册");
            });
    }

    @Test
    void middleware_shouldBeReachableByAgentFactoryInjectionStyle() {
        runner().withPropertyValues(ENABLED_KEY).run(context -> {
            // 与 AdminAgentInstanceFactory 的注入口径一致：拿不到就等于中间件没挂上智能体链路
            ObjectProvider<OtelTracingMiddleware> provider =
                context.getBeanProvider(OtelTracingMiddleware.class);
            assertNotNull(provider.getIfAvailable(), "开启时 Agent 工厂必须能拿到 OTel 追踪中间件");
        });
    }

    /** 只装 admin 的 OTel 装配类，不拉起整个 admin 上下文（不连库、不起 web 容器）。 */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(AdminOtelTracingConfig.class);
    }
}
