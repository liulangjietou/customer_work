package com.richard.fyoung.customerwork.safety.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 安全响应头门控。 */
class SecurityHeadersWebFilterTest {

    @Test
    void apiResponse_shouldSetBrowserSecurityHeadersAndNoStore() {
        SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("https://example.test/api/customer/chat"));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertEquals("nosniff", exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("DENY", exchange.getResponse().getHeaders().getFirst("X-Frame-Options"));
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
        assertEquals("max-age=31536000; includeSubDomains",
            exchange.getResponse().getHeaders().getFirst("Strict-Transport-Security"));
    }

    @Test
    void httpResponse_shouldNotPretendHstsIsEffective() {
        SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("http://localhost/swagger-ui/index.html"));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertNull(exchange.getResponse().getHeaders().getFirst("Strict-Transport-Security"));
        assertNull(exchange.getResponse().getHeaders().getCacheControl());
    }
}
