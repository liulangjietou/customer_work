package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * W3C trace-context 关联过滤器（分布式追踪的轻量对接，零外部依赖）。
 *
 * <p>解析上游（API 网关 / 调用方，通常已跑 OpenTelemetry）传入的 {@code traceparent} 请求头
 * （格式 {@code 00-<32hex traceId>-<16hex spanId>-<flags>}），把其中的 <b>trace-id</b> 放入
 * Reactor Context（键 {@code traceId}），经 {@link MdcContextLifter} 落到 MDC 与日志。这样本服务的
 * 日志会带上与上游 Jaeger/Tempo 链路<b>同一个 traceId</b>，实现"外部分布式链路 ↔ 本服务日志"的交叉关联，
 * 无需引入 OpenTelemetry SDK。</p>
 *
 * <p>说明：本过滤器只做<b>关联（correlation）</b>——让 traceId 贯通日志；<b>在进程内真正采集 span
 * 并导出到 OTLP/Jaeger</b> 需引入 OpenTelemetry SDK 依赖（当前构建未包含），属基础设施扩展点，
 * 可另行声明框架 {@code Tracer} Bean 接入（见 {@link TracingConfig}）。仅当
 * {@code observability.trace-correlation-enabled=true}（默认）时生效。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // 紧随 RequestIdWebFilter 之后
public class TraceContextWebFilter implements WebFilter {

    /** W3C trace-context 标准请求头。 */
    public static final String TRACEPARENT_HEADER = "traceparent";

    /** traceparent 结构：version(2) - traceId(32) - spanId(16) - flags(2)，均为小写十六进制。 */
    private static final Pattern TRACEPARENT = Pattern.compile(
        "^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    /** 全零 traceId 无效（W3C 规范：不得为全零）。 */
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    private final boolean enabled;

    public TraceContextWebFilter(CustomerWorkProperties properties) {
        this.enabled = properties.getObservability().isTraceCorrelationEnabled();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String traceId = extractTraceId(exchange.getRequest().getHeaders().getFirst(TRACEPARENT_HEADER));
        if (traceId == null) {
            return chain.filter(exchange);
        }
        // 回写响应头便于调用方核对，并放入 Reactor Context 供 MDC 同步
        exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
        return chain.filter(exchange)
            .contextWrite(ctx -> ctx.put(MdcContextLifter.TRACE_ID_KEY, traceId));
    }

    /**
     * 从 traceparent 头解析出 trace-id；格式非法或全零返回 null。
     */
    public static String extractTraceId(String traceparent) {
        if (!StringUtils.hasText(traceparent)) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(traceparent.trim());
        if (!matcher.matches()) {
            return null;
        }
        String traceId = matcher.group(1);
        return INVALID_TRACE_ID.equals(traceId) ? null : traceId;
    }
}
