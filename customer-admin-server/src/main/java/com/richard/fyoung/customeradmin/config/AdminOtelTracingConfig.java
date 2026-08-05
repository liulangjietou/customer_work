package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.OtelTracingConfig;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * admin 侧 OpenTelemetry 接入装配（admin 排除了 starter 自动装配，这里显式装配，
 * 照 {@code AgentCallStatsStoreConfig} / {@code AdminSensitiveWordFilterConfig} 的先例）。
 *
 * <p><b>整块由 {@code admin.observability.otel.enabled=true} 门控</b>，默认关闭：关闭时一个 Bean 都不装配，
 * 框架的 {@code OtelTracingMiddleware} 也不存在，链路上零开销。</p>
 *
 * <p><b>SDK 构建逻辑复用 starter 的 {@link OtelTracingConfig}</b>，不在 admin 复制一份：那边已经把
 * Resource/BatchSpanProcessor/OTLP 导出器/parentBased 采样器以及「GlobalOpenTelemetry 重复注册容忍」
 * 的语义定死了，复制一份必然随时间漂移。starter 那个类的 {@code @Configuration} 与
 * {@code @ConditionalOnProperty} 只在被容器当作配置类解析时才有意义——这里是把它当<b>普通构建器</b>用：
 * 现场 new 一个 {@link CustomerWorkProperties} 填上 admin 的配置值，调其构建方法拿到 SDK。
 * 由 @Bean 方法返回的对象不会被 Spring 再当配置类解析，故不会连带把 starter 的其它 Bean 拉进来。</p>
 *
 * <p><b>本批不做 admin 的 HTTP server span</b>：starter 的 {@code OtelServerTraceWebFilter} 是 WebFlux
 * ({@code org.springframework.web.server.WebFilter}) 实现，而 admin-server 是 Spring MVC（见
 * {@code spring.main.web-application-type=servlet}），那个过滤器在 MVC 容器里根本不会被调用。
 * 因此 admin 侧的 span 树<b>以 agent 为根</b>（agent / model / tool 三段由 {@link OtelTracingMiddleware}
 * 经 {@code GlobalOpenTelemetry} 导出），没有外层的 HTTP 入口 span。后台管理链路的观测重点是"这次智能体
 * 调用慢在哪一段"，agent 为根已经够用；真需要 HTTP 入口 span 时补一个 servlet Filter 或直接挂 OTel
 * Java Agent（Java Agent 会先注册全局 SDK，本类的注册自动让位，见 starter 类注释）。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "admin.observability.otel", name = "enabled", havingValue = "true")
public class AdminOtelTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminOtelTracingConfig.class);

    /**
     * 构建并注册全局 OTel SDK。
     *
     * <p>Bean 销毁走 Spring 推断出的 {@code close()}（{@link OpenTelemetrySdk} 实现 {@code Closeable}，
     * close 内部即 shutdown + 等待 flush），容器关闭时把 BatchSpanProcessor 队列里未导出的 span 送走，
     * 不需要额外写 {@code @PreDestroy}。</p>
     */
    @Bean
    public OpenTelemetrySdk adminOpenTelemetrySdk(
            @Value("${admin.observability.otel.endpoint:http://localhost:4317}") String endpoint,
            @Value("${admin.observability.otel.service-name:customer-admin}") String serviceName,
            @Value("${admin.observability.otel.sampler-ratio:1.0}") double samplerRatio) {
        CustomerWorkProperties runtime = new CustomerWorkProperties();
        CustomerWorkProperties.Observability.Otel cfg = runtime.getObservability().getOtel();
        cfg.setEnabled(true);
        cfg.setEndpoint(endpoint);
        cfg.setServiceName(serviceName);
        cfg.setSamplerRatio(samplerRatio);
        // 复用 starter 的构建逻辑（含 GlobalOpenTelemetry 注册与重复注册容忍）
        return new OtelTracingConfig(runtime).customerWorkOpenTelemetrySdk();
    }

    /**
     * 框架自带的 OTel 追踪中间件：按 agent / model / tool 三段出 span，由
     * {@code AdminAgentInstanceFactory} 显式挂到每个 workspace 智能体链上
     * （admin 的工厂是构造器逐个注入 + 显式挂链，没有"自动吸收 Middleware Bean"那套机制）。
     *
     * <p>形参声明对 SDK 的依赖，保证中间件建好时全局 SDK 已注册（中间件内部走 GlobalOpenTelemetry 取 Tracer）。</p>
     */
    @Bean
    public OtelTracingMiddleware adminOtelTracingMiddleware(OpenTelemetrySdk adminOpenTelemetrySdk) {
        log.info("[OTEL] admin agent tracing middleware wired");
        return new OtelTracingMiddleware();
    }
}
