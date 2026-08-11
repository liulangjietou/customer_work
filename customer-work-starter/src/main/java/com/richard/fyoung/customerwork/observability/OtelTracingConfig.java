package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import com.richard.fyoung.customerwork.infra.config.properties.ObservabilityProperties;

/**
 * OpenTelemetry SDK 接入装配（可观测「最后一公里」：span 真正采集并导出到 Collector/Tempo）。
 *
 * <p>框架 {@link OtelTracingMiddleware} 只依赖 otel-api，通过 {@code GlobalOpenTelemetry} 取 Tracer——
 * 没有 SDK 时拿到的是 no-op 实现，全链路近零开销；本类补上 SDK 侧三件套：</p>
 * <ul>
 *   <li>{@link Resource} 带 {@code service.name}，链路后台按之区分服务；</li>
 *   <li>{@link BatchSpanProcessor} + {@link OtlpGrpcSpanExporter}：批量异步导出，
 *       导出失败/后端不可达只在 OTel 内部重试与丢弃，<b>天然不阻塞也不影响业务主链路</b>，
 *       故这里无需再额外做隔离或兜底；</li>
 *   <li>{@link Sampler#parentBased} 包裹比例采样：上游已决定采样的链路始终跟随父级，
 *       避免同一条分布式链路在本服务这里断成两截。</li>
 * </ul>
 *
 * <p><b>GlobalOpenTelemetry 只能 set 一次</b>（重复 set 抛 IllegalStateException）。这里选择
 * <b>容忍</b>而非 fail fast：重复注册的现实场景是宿主已挂了 OTel Java Agent（Agent 先注册了自己的
 * 全局 SDK），此时业务侧应当让位、复用 Agent 的 SDK，让应用起不来是过度反应。捕获后记 info 并
 * 继续——中间件仍能从 GlobalOpenTelemetry 拿到已注册的那套 SDK 正常出 span。测试里用
 * {@code GlobalOpenTelemetry.resetForTest()} 清理全局状态。</p>
 *
 * <p>仅当 {@code customer-work.observability.otel.enabled=true} 时整组 Bean 才装配，关闭时启动零开销。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "customer-work.observability.otel", name = "enabled", havingValue = "true")
public class OtelTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(OtelTracingConfig.class);

    /** OTel 语义约定的服务名资源属性键。 */
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    /** 容器关闭时等待 batch flush 的上限，超时即放弃，不拖慢停机。 */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final ObservabilityProperties.Otel otelProperties;

    /** 持有 SDK 引用供 {@link #shutdown()} 优雅关闭（flush 未导出的 batch）。 */
    private OpenTelemetrySdk sdk;

    public OtelTracingConfig(CustomerWorkProperties properties) {
        this.otelProperties = properties.getObservability().getOtel();
    }

    /**
     * 构建并注册全局 OTel SDK。返回 SDK 实例本身而非 {@code OpenTelemetry} 接口，
     * 便于 {@link OtelServerTraceWebFilter} 直接注入取 Tracer/Propagator（同时形成 Bean 依赖，
     * 保证过滤器建好时全局 SDK 已就绪）。
     */
    @Bean
    public OpenTelemetrySdk customerWorkOpenTelemetrySdk() {
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(SERVICE_NAME, otelProperties.getServiceName())));

        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(otelProperties.getEndpoint())
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(otelProperties.getSamplerRatio())))
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build();

        this.sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

        registerGlobal(this.sdk);
        log.info("[OTEL] sdk wired, service={} endpoint={} samplerRatio={}",
            otelProperties.getServiceName(), otelProperties.getEndpoint(), otelProperties.getSamplerRatio());
        return this.sdk;
    }

    /**
     * 框架自带的 OTel 追踪中间件：按 agent / model / tool 三段出 span。
     * 声明成 Bean 后由 {@code CustomerServiceAgentFactory} 的 {@code ObjectProvider<MiddlewareBase>}
     * 自动吸收挂到 Agent 链上，无需改工厂。
     *
     * <p>依赖全局 SDK 先注册（形参声明依赖即可，中间件内部走 GlobalOpenTelemetry 取 Tracer）。</p>
     */
    @Bean
    public OtelTracingMiddleware otelTracingMiddleware(OpenTelemetrySdk customerWorkOpenTelemetrySdk) {
        log.info("[OTEL] agent tracing middleware wired");
        return new OtelTracingMiddleware();
    }

    /** 注册为全局 SDK；已被别人（典型是 OTel Java Agent）注册过则让位，见类注释。 */
    private void registerGlobal(OpenTelemetrySdk instance) {
        try {
            GlobalOpenTelemetry.set(instance);
        } catch (IllegalStateException e) {
            log.info("[OTEL] global instance already registered by others, reuse it: {}", e.getMessage());
        }
    }

    /** 容器关闭时优雅停机：flush 掉 BatchSpanProcessor 队列里未导出的 span，避免尾部链路丢失。 */
    @PreDestroy
    public void shutdown() {
        if (sdk == null) {
            return;
        }
        try {
            sdk.getSdkTracerProvider().shutdown().join(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.info("[OTEL] sdk shutdown completed");
        } catch (Exception e) {
            log.error("otel sdk shutdown failed, code={}", "OBS-OTEL-SHUTDOWN-FAIL", e);
        }
    }
}
