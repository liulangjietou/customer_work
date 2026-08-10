package com.richard.fyoung.customerwork.safety.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求 ID 过滤器单测：自动生成 / 沿用上游 X-Request-Id 并写回响应头。
 * @author owlzhangfq@gmail.com
 */
class RequestIdWebFilterTest {

    private final RequestIdWebFilter filter = new RequestIdWebFilter();

    @Test
    void shouldGenerateRequestId_whenAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat").build());

        filter.filter(exchange, ex -> Mono.empty()).block();

        String rid = exchange.getResponse().getHeaders().getFirst(RequestIdWebFilter.HEADER);
        assertTrue(rid != null && !rid.isBlank(), "应生成 X-Request-Id");
    }

    @Test
    void shouldPreserveIncomingRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .header(RequestIdWebFilter.HEADER, "trace-abc").build());

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals("trace-abc",
            exchange.getResponse().getHeaders().getFirst(RequestIdWebFilter.HEADER));
    }
}
