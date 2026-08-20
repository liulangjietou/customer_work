package com.richard.fyoung.customerwork.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 入站 SERVER span 过滤器（OTel 链路的根，把 agent/model/tool span 挂到同一棵树上）。
 *
 * <p>做三件事：</p>
 * <ol>
 *   <li><b>接上游链路</b>：用 W3C 传播器从请求头（{@code traceparent}）提取父上下文，
 *       本 span 作为其子节点，实现跨服务链路续接；无上游头时本 span 即链路根；</li>
 *   <li><b>把 OTel Context 存进 Reactor Context</b>（{@link ContextPropagationOperator}）：
 *       框架 {@code OtelTracingMiddleware} 正是从 Reactor ContextView 取父上下文的，
 *       没有这一步，agent/model/tool span 会各自成为孤立的根 span，链路树散架；</li>
 *   <li><b>traceId 落 MDC</b>：写入 {@link MdcContextLifter#TRACE_ID_KEY}，让本服务日志与
 *       Tempo/Jaeger 里的链路共享同一个 traceId，日志与链路可互相跳转。</li>
 * </ol>
 *
 * <p><b>与 {@link TraceContextWebFilter} 的关系</b>：那个过滤器零外部依赖，只在请求<b>带</b>
 * {@code traceparent} 头时把 traceId 写进 Reactor Context；本过滤器在 OTel 开启时对<b>所有</b>请求写
 * （无上游头时用本地新生成的 traceId）。两者并存无冲突：有上游头时本 span 继承同一 traceId，
 * 写入值本来就相等；本过滤器排在其后、contextWrite 更靠近上游，覆盖亦无害。</p>
 *
 * <p>{@code /actuator/**} 健康检查与指标抓取按秒级轮询，打 span 只会淹没链路后台且无诊断价值，直接跳过。</p>
 *
 * <p>仅当 {@code customer-work.observability.otel.enabled=true} 时装配（随 {@link OtelTracingConfig}）。</p>
 * @author owlzhangfq@gmail.com
 */
// 仅响应式栈装配：本类是 WebFlux 的 WebFilter，在 Servlet 栈（customer-admin-server）下
// 既不会生效也不该存在。没有这个条件时，下游 Servlet 模块只能整体 exclude starter 的入口
// 自动装配来躲开它，代价是全部域装配一并让位、几十个 Bean 要手工重装。
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Component
@ConditionalOnProperty(prefix = "customer-work.observability.otel", name = "enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 2)   // 紧随 TraceContextWebFilter(+1) 之后
public class OtelServerTraceWebFilter implements WebFilter {

    /** Tracer 名（instrumentation scope），链路后台按之区分埋点来源。 */
    private static final String INSTRUMENTATION_NAME = "com.richard.fyoung.customerwork.http";
    /** 不打 span 的路径前缀：健康检查 / 指标抓取。 */
    private static final String ACTUATOR_PREFIX = "/actuator";
    /** 回写给调用方核对的响应头（与 TraceContextWebFilter 同名同义）。 */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    /** 500 及以上视为服务端错误，span 置 ERROR。 */
    private static final int SERVER_ERROR_STATUS = 500;

    /** 从 Spring 的 HttpHeaders 取值给 OTel 传播器（大小写不敏感，HttpHeaders 自身保证）。 */
    private static final TextMapGetter<HttpHeaders> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpHeaders carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(HttpHeaders carrier, String key) {
            return carrier == null ? null : carrier.getFirst(key);
        }
    };

    private final Tracer tracer;
    private final TextMapPropagator propagator;

    public OtelServerTraceWebFilter(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }
        String method = exchange.getRequest().getMethod().name();

        // 从请求头提取父上下文：有 traceparent 则续接上游链路，没有则得到 root
        Context parentContext = propagator.extract(Context.root(),
            exchange.getRequest().getHeaders(), HEADER_GETTER);

        Span span = tracer.spanBuilder("HTTP " + method + " " + path)
            .setSpanKind(SpanKind.SERVER)
            .setParent(parentContext)
            .setAttribute("http.request.method", method)
            .setAttribute("url.path", path)
            .startSpan();

        Context otelContext = span.storeInContext(parentContext);
        String traceId = span.getSpanContext().getTraceId();
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);

        // 三态终止各结算一次（complete / error / cancel），CAS 保证 span 只 end 一次
        AtomicBoolean ended = new AtomicBoolean(false);
        return chain.filter(exchange)
            .doOnEach(signal -> {
                if (signal.isOnComplete() || signal.isOnError()) {
                    endSpan(span, ended, exchange, signal.getThrowable(), null);
                }
            })
            .doOnCancel(() -> endSpan(span, ended, exchange, null, "cancelled"))
            // contextWrite 作用于上游：链路上所有下游算子（含框架中间件）都能读到这两个键
            .contextWrite(ctx -> ContextPropagationOperator.storeOpenTelemetryContext(
                ctx.put(MdcContextLifter.TRACE_ID_KEY, traceId), otelContext));
    }

    /** 结算 span：记录响应码，按异常 / 取消 / 状态码置 status 后 end。 */
    private void endSpan(Span span, AtomicBoolean ended, ServerWebExchange exchange,
                         Throwable error, String cancelReason) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        Integer status = exchange.getResponse().getStatusCode() == null
            ? null : exchange.getResponse().getStatusCode().value();
        if (status != null) {
            span.setAttribute("http.response.status_code", status.longValue());
        }
        if (error != null) {
            span.setStatus(StatusCode.ERROR, error.getMessage());
            span.recordException(error);
        } else if (cancelReason != null) {
            span.setStatus(StatusCode.ERROR, cancelReason);
        } else if (status != null && status >= SERVER_ERROR_STATUS) {
            span.setStatus(StatusCode.ERROR);
        } else {
            span.setStatus(StatusCode.OK);
        }
        span.end();
    }
}
