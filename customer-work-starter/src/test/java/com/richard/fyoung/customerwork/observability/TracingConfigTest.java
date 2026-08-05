package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.tracing.TracerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 框架原生 Tracer 装配单测：三档语义（关闭 / LoggingTracer / 让位给 OTel）。
 *
 * <p>{@code TracerRegistry} 是框架全局单例，每个用例前后 {@code resetToNoop()} 复位，
 * 避免污染同 JVM 的其它测试。</p>
 * @author owlzhangfq@gmail.com
 */
class TracingConfigTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        TracerRegistry.disableTracingHook();
        TracerRegistry.resetToNoop();
    }

    @Test
    void shouldNotRegister_whenTracingDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getObservability().setTracingEnabled(false);

        new TracingConfig(props).register();

        assertFalse(TracerRegistry.get() instanceof LoggingTracer, "关闭时保持 noop");
    }

    @Test
    void shouldRegisterLoggingTracer_whenTracingEnabledAndOtelDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getObservability().setTracingEnabled(true);

        new TracingConfig(props).register();

        assertInstanceOf(LoggingTracer.class, TracerRegistry.get(), "原有语义不变：注册 LoggingTracer");
    }

    /** OTel 接管时让位：否则同一批 span 会既进日志又进 OTLP，噪声与开销翻倍。 */
    @Test
    void shouldSkipLoggingTracer_whenOtelEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getObservability().setTracingEnabled(true);
        props.getObservability().getOtel().setEnabled(true);

        new TracingConfig(props).register();

        assertFalse(TracerRegistry.get() instanceof LoggingTracer,
            "OTel 开启时不注册 LoggingTracer，避免双份 span");
    }
}
