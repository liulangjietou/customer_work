package com.example.customerwork.security;

import com.example.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流过滤器单测（接入层安全，固定时间窗）。
 * @author owlzhangfq@gmail.com
 */
class RateLimitWebFilterTest {

    private CustomerWorkProperties props(boolean enabled, int rpm) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSecurity().getRateLimit().setEnabled(enabled);
        props.getSecurity().getRateLimit().setRequestsPerMinute(rpm);
        return props;
    }

    @Test
    void allow_shouldEnforcePerWindowLimit() {
        RateLimitWebFilter filter = new RateLimitWebFilter(props(true, 2));
        assertTrue(filter.allow("client-1", 2), "第 1 次应放行");
        assertTrue(filter.allow("client-1", 2), "第 2 次应放行");
        assertFalse(filter.allow("client-1", 2), "第 3 次应被限流");
        // 不同客户端独立计数
        assertTrue(filter.allow("client-2", 2), "另一客户端不受影响");
    }

    @Test
    void filter_shouldReturn429_whenExceeded() {
        RateLimitWebFilter filter = new RateLimitWebFilter(props(true, 1));
        // 第一次放行
        runOnce(filter, "1.2.3.4");
        // 第二次应 429
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .remoteAddress(new java.net.InetSocketAddress("1.2.3.4", 55000)).build());
        AtomicInteger chainCalls = new AtomicInteger();
        filter.filter(exchange, ex -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertEquals(0, chainCalls.get(), "超限请求不应进入业务链");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    void filter_shouldPass_whenDisabled() {
        RateLimitWebFilter filter = new RateLimitWebFilter(props(false, 1));
        AtomicInteger chainCalls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/customer/chat")
                    .remoteAddress(new java.net.InetSocketAddress("1.2.3.4", 55000)).build());
            filter.filter(exchange, ex -> {
                chainCalls.incrementAndGet();
                return Mono.empty();
            }).block();
        }
        assertEquals(5, chainCalls.get(), "关闭限流时全部放行");
    }

    private void runOnce(RateLimitWebFilter filter, String ip) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .remoteAddress(new java.net.InetSocketAddress(ip, 55000)).build());
        filter.filter(exchange, ex -> Mono.empty()).block();
    }
}
