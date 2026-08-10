package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.ratelimit.InMemoryRateLimitRuleStore;
import com.richard.fyoung.customerwork.safety.security.ratelimit.RateLimitAlgorithm;
import com.richard.fyoung.customerwork.safety.security.ratelimit.RateLimitDimension;
import com.richard.fyoung.customerwork.safety.security.ratelimit.RateLimitRule;
import com.richard.fyoung.customerwork.safety.security.ratelimit.RateLimitRuleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /** 只有全局兜底层的过滤器（规则层未装配的形态，也就是规则化之前的行为）。 */
    private RateLimitWebFilter newFilter(CustomerWorkProperties props) {
        return new RateLimitWebFilter(props, null);
    }

    /** 带规则层的过滤器。 */
    private RateLimitWebFilter newFilter(CustomerWorkProperties props, RateLimitRuleProvider ruleProvider) {
        ObjectProvider<RateLimitRuleProvider> provider = mockProvider(ruleProvider);
        return new RateLimitWebFilter(props, provider);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RateLimitRuleProvider> mockProvider(RateLimitRuleProvider ruleProvider) {
        ObjectProvider<RateLimitRuleProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ruleProvider);
        return provider;
    }

    /** 构造一个只含指定规则的规则层。 */
    private RateLimitRuleProvider ruleProviderOf(RateLimitRule... rules) {
        InMemoryRateLimitRuleStore store = new InMemoryRateLimitRuleStore();
        for (RateLimitRule rule : rules) {
            store.save(rule);
        }
        return new RateLimitRuleProvider(store, false);
    }

    @Test
    void allow_shouldEnforcePerWindowLimit() {
        RateLimitWebFilter filter = newFilter(props(true, 2));
        assertTrue(filter.allow("client-1", 2), "第 1 次应放行");
        assertTrue(filter.allow("client-1", 2), "第 2 次应放行");
        assertFalse(filter.allow("client-1", 2), "第 3 次应被限流");
        // 不同客户端独立计数
        assertTrue(filter.allow("client-2", 2), "另一客户端不受影响");
    }

    @Test
    void filter_shouldReturn429_whenExceeded() {
        RateLimitWebFilter filter = newFilter(props(true, 1));
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
        RateLimitWebFilter filter = newFilter(props(false, 1));
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

    // ======== 滑动窗口限流测试 ========

    @Test
    void slidingWindow_shouldEnforceLimitWithinWindow() {
        RateLimitWebFilter filter = newFilter(props(true, 3));
        // 窗口 60 秒，limit=3
        assertTrue(filter.allowSliding("client-sw", 3, 60), "第 1 次应放行");
        assertTrue(filter.allowSliding("client-sw", 3, 60), "第 2 次应放行");
        assertTrue(filter.allowSliding("client-sw", 3, 60), "第 3 次应放行");
        assertFalse(filter.allowSliding("client-sw", 3, 60), "第 4 次应被限流");
    }

    @Test
    void slidingWindow_shouldAllowAfterWindowExpires() throws InterruptedException {
        RateLimitWebFilter filter = newFilter(props(true, 2));
        // 窗口 1 秒，limit=2
        assertTrue(filter.allowSliding("client-exp", 2, 1));
        assertTrue(filter.allowSliding("client-exp", 2, 1));
        assertFalse(filter.allowSliding("client-exp", 2, 1), "窗口内第 3 次应被限流");
        // 等待窗口过期
        Thread.sleep(1100);
        assertTrue(filter.allowSliding("client-exp", 2, 1), "窗口过期后应放行");
    }

    @Test
    void slidingWindow_shouldIsolateClients() {
        RateLimitWebFilter filter = newFilter(props(true, 2));
        assertTrue(filter.allowSliding("client-a", 2, 60));
        assertTrue(filter.allowSliding("client-a", 2, 60));
        assertFalse(filter.allowSliding("client-a", 2, 60));
        // 不同客户端独立计数
        assertTrue(filter.allowSliding("client-b", 2, 60), "另一客户端不受影响");
    }

    // ---------------- 规则层 ----------------

    @Test
    void rule_shouldOverrideGlobalConfig_whenPathMatches() {
        // 全局兜底放得很松（1000/分钟），规则把 /api/customer/chat 收紧到 2 次
        CustomerWorkProperties props = props(true, 1000);
        RateLimitRule rule = new RateLimitRule(1L, "chat-strict", "/api/customer/chat",
            RateLimitDimension.IP, 2, RateLimitAlgorithm.FIXED_WINDOW, 60, 0, true);
        RateLimitWebFilter filter = newFilter(props, ruleProviderOf(rule));

        assertEquals(null, statusOf(filter, "/api/customer/chat", "9.9.9.1"), "第 1 次应放行");
        assertEquals(null, statusOf(filter, "/api/customer/chat", "9.9.9.1"), "第 2 次应放行");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf(filter, "/api/customer/chat", "9.9.9.1"),
            "规则阈值优先于全局兜底，第 3 次应被限流");
    }

    @Test
    void rule_shouldFallBackToGlobalConfig_whenPathNotMatched() {
        // 规则只管 /api/customer/chat，另一条路径落到全局兜底（限 1 次）
        CustomerWorkProperties props = props(true, 1);
        RateLimitRule rule = new RateLimitRule(1L, "chat-loose", "/api/customer/chat",
            RateLimitDimension.IP, 1000, RateLimitAlgorithm.FIXED_WINDOW, 60, 0, true);
        RateLimitWebFilter filter = newFilter(props, ruleProviderOf(rule));

        assertEquals(null, statusOf(filter, "/api/customer/ticket", "9.9.9.2"), "第 1 次走全局兜底应放行");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf(filter, "/api/customer/ticket", "9.9.9.2"),
            "未命中规则时按全局兜底限流");
    }

    @Test
    void rule_shouldMatchByPriority_firstWins() {
        // 两条规则都匹配同一路径，优先级小的（严格那条）先生效，且不与另一条叠加
        RateLimitRule strict = new RateLimitRule(1L, "strict", "/api/customer",
            RateLimitDimension.GLOBAL, 1, RateLimitAlgorithm.FIXED_WINDOW, 60, 0, true);
        RateLimitRule loose = new RateLimitRule(2L, "loose", "/api/customer",
            RateLimitDimension.GLOBAL, 100, RateLimitAlgorithm.FIXED_WINDOW, 60, 5, true);
        RateLimitWebFilter filter = newFilter(props(false, 1), ruleProviderOf(loose, strict));

        assertEquals(null, statusOf(filter, "/api/customer/chat", "9.9.9.3"), "第 1 次应放行");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf(filter, "/api/customer/chat", "9.9.9.3"),
            "优先级小的严格规则先匹配，第 2 次应被限流");
    }

    @Test
    void rule_globalDimension_shouldShareQuotaAcrossClients() {
        RateLimitRule rule = new RateLimitRule(1L, "global-quota", "/api/customer",
            RateLimitDimension.GLOBAL, 1, RateLimitAlgorithm.FIXED_WINDOW, 60, 0, true);
        RateLimitWebFilter filter = newFilter(props(false, 1), ruleProviderOf(rule));

        assertEquals(null, statusOf(filter, "/api/customer/chat", "10.0.0.1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf(filter, "/api/customer/chat", "10.0.0.2"),
            "GLOBAL 维度下不同 IP 共享同一份配额");
    }

    @Test
    void ruleProvider_shouldReturnNull_whenNoRuleMatches() {
        RateLimitRule rule = new RateLimitRule(1L, "chat", "/api/customer/chat",
            RateLimitDimension.IP, 1, RateLimitAlgorithm.FIXED_WINDOW, 60, 0, true);
        assertNull(ruleProviderOf(rule).match("/api/other"), "路径不匹配时不应返回规则");
    }

    private void runOnce(RateLimitWebFilter filter, String ip) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .remoteAddress(new java.net.InetSocketAddress(ip, 55000)).build());
        filter.filter(exchange, ex -> Mono.empty()).block();
    }

    /** 跑一次请求并返回响应状态码（放行时为 null）。 */
    private HttpStatus statusOf(RateLimitWebFilter filter, String path, String ip) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post(path)
                .remoteAddress(new java.net.InetSocketAddress(ip, 55000)).build());
        filter.filter(exchange, ex -> Mono.empty()).block();
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }
}
