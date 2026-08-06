package com.richard.fyoung.customerwork.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP SERVER span 过滤器单测（用 InMemorySpanExporter 断言 span，不起真实服务器、不连 Collector）。
 *
 * <p>覆盖：SERVER span 生成与命名、traceparent 续接上游父链路、Reactor Context 同时携带
 * OTel Context（框架中间件的父上下文来源）与 traceId（MDC 日志关联）、actuator 跳过、
 * 异常与 5xx 置 ERROR。</p>
 * @author owlzhangfq@gmail.com
 */
class OtelServerTraceWebFilterTest {

    /** 上游传入的 traceparent（W3C 标准示例值）。 */
    private static final String PARENT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + PARENT_TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk sdk;
    private OtelServerTraceWebFilter filter;

    @BeforeEach
    void setUp() {
        // 全局单例不参与本测试（过滤器走构造注入），但同 JVM 其它用例可能污染，进出都清一次
        GlobalOpenTelemetry.resetForTest();
        exporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
        filter = new OtelServerTraceWebFilter(sdk);
    }

    @AfterEach
    void tearDown() {
        sdk.getSdkTracerProvider().close();
        GlobalOpenTelemetry.resetForTest();
    }

    /** 无上游 traceparent：本 span 即链路根，SERVER kind，名字为 "HTTP <method> <path>"。 */
    @Test
    void shouldProduceServerSpan_whenNoParentHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/chat"));

        filter.filter(exchange, emptyChain()).block();

        SpanData span = onlySpan();
        assertEquals("HTTP GET /api/chat", span.getName());
        assertEquals(SpanKind.SERVER, span.getKind());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertTrue(span.getSpanContext().isValid(), "本地新生成的链路根 span 上下文有效");
        assertEquals("GET", span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")));
        // 响应头回写 traceId，便于调用方核对
        assertEquals(span.getTraceId(), exchange.getResponse().getHeaders().getFirst("X-Trace-Id"));
    }

    /** 带 traceparent：本 span 挂到上游链路上（同 traceId，父 spanId 即头里的 spanId）。 */
    @Test
    void shouldJoinUpstreamTrace_whenTraceparentPresent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/chat").header("traceparent", TRACEPARENT));

        filter.filter(exchange, emptyChain()).block();

        SpanData span = onlySpan();
        assertEquals(PARENT_TRACE_ID, span.getTraceId(), "续接上游 traceId，跨服务链路不断");
        assertEquals(PARENT_SPAN_ID, span.getParentSpanId(), "父 span 取自 traceparent");
    }

    /** Reactor Context 同时带 OTel Context（框架中间件据此嵌套子 span）与 traceId（MDC 日志关联）。 */
    @Test
    void shouldWriteOtelContextAndTraceIdIntoReactorContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/chat").header("traceparent", TRACEPARENT));
        AtomicReference<String> mdcTraceId = new AtomicReference<>();
        AtomicReference<String> otelSpanId = new AtomicReference<>();

        WebFilterChain chain = ex -> Mono.deferContextual(cv -> {
            mdcTraceId.set(cv.getOrDefault(MdcContextLifter.TRACE_ID_KEY, null));
            // 框架 OtelTracingMiddleware 正是用这个方法从 Reactor Context 取父上下文
            io.opentelemetry.context.Context otel =
                ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                    cv, io.opentelemetry.context.Context.root());
            otelSpanId.set(io.opentelemetry.api.trace.Span.fromContext(otel)
                .getSpanContext().getSpanId());
            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        SpanData span = onlySpan();
        assertEquals(PARENT_TRACE_ID, mdcTraceId.get(), "traceId 落 Reactor Context，经 MdcContextLifter 进 MDC");
        assertEquals(span.getSpanId(), otelSpanId.get(),
            "Reactor Context 里的 OTel Context 指向本 SERVER span，子 span 才能正确嵌套");
    }

    /** actuator 路径不打 span（健康检查/指标抓取高频且无诊断价值）。 */
    @Test
    void shouldSkipActuatorPaths() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health"));

        filter.filter(exchange, emptyChain()).block();

        assertTrue(exporter.getFinishedSpanItems().isEmpty(), "actuator 请求不产生 span");
    }

    /** 下游抛异常：span 置 ERROR 并记录异常，错误信号原样透传给调用方。 */
    @Test
    void shouldMarkSpanError_whenDownstreamFails() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/chat"));
        RuntimeException boom = new RuntimeException("downstream boom");
        AtomicReference<Throwable> seen = new AtomicReference<>();

        filter.filter(exchange, ex -> Mono.error(boom))
            .doOnError(seen::set)
            .onErrorResume(e -> Mono.empty())
            .block();

        assertEquals(boom, seen.get(), "主链路错误信号原样透传");
        SpanData span = onlySpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(1, span.getEvents().size(), "异常记录为 span event");
    }

    /** 5xx 响应：即便没有异常也置 ERROR。 */
    @Test
    void shouldMarkSpanError_whenServerErrorStatus() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/chat"));

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return Mono.empty();
        }).block();

        SpanData span = onlySpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(500L, span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")));
    }

    private WebFilterChain emptyChain() {
        return (ServerWebExchange ex) -> Mono.empty();
    }

    private SpanData onlySpan() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "每个请求恰好一个 SERVER span");
        SpanData span = spans.get(0);
        assertNotNull(span);
        return span;
    }
}
