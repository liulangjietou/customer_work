package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批操作员身份鉴权过滤器单测（堵住"任何人可冒充任意坐席放行退款"的身份伪造漏洞）。
 * @author owlzhangfq@gmail.com
 */
class ApprovalAuthWebFilterTest {

    private CustomerWorkProperties propsWithApprovalAuth(boolean enabled, Map<String, String> operators) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSecurity().getApprovalAuth().setEnabled(enabled);
        props.getSecurity().getApprovalAuth().setOperators(operators);
        return props;
    }

    private MockServerWebExchange run(CustomerWorkProperties props, MockServerHttpRequest request,
                                      AtomicBoolean chainCalled) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };
        new ApprovalAuthWebFilter(props).filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void shouldPass_whenDisabled_withoutResolvingOperator() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockServerWebExchange exchange = run(
            propsWithApprovalAuth(false, Map.of("t1", "alice")),
            MockServerHttpRequest.post("/api/customer/approvals/AP-1/approve").build(),
            chainCalled);
        assertTrue(chainCalled.get(), "鉴权关闭时应放行");
        assertNull(exchange.getAttribute(ApprovalAuthWebFilter.RESOLVED_OPERATOR_ATTR),
            "鉴权关闭时不应解析出操作员身份");
    }

    @Test
    void shouldReject_whenEnabledAndNoToken() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockServerWebExchange exchange = run(
            propsWithApprovalAuth(true, Map.of("t1", "alice")),
            MockServerHttpRequest.post("/api/customer/approvals/AP-1/approve").build(),
            chainCalled);
        assertFalse(chainCalled.get(), "无 token 不应放行");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldReject_whenTokenUnknown() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockServerWebExchange exchange = run(
            propsWithApprovalAuth(true, Map.of("t1", "alice")),
            MockServerHttpRequest.post("/api/customer/approvals/AP-1/approve")
                .header("X-Approval-Token", "wrong-token").build(),
            chainCalled);
        assertFalse(chainCalled.get(), "未知 token 不应放行");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldResolveOperatorFromToken_andAllowApprove() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockServerWebExchange exchange = run(
            propsWithApprovalAuth(true, Map.of("t1", "alice", "t2", "bob")),
            MockServerHttpRequest.post("/api/customer/approvals/AP-1/approve")
                .header("X-Approval-Token", "t2").build(),
            chainCalled);
        assertTrue(chainCalled.get(), "合法 token 应放行");
        assertEquals("bob", exchange.getAttribute(ApprovalAuthWebFilter.RESOLVED_OPERATOR_ATTR),
            "应解析出 token 对应的操作员姓名");
    }

    @Test
    void shouldResolveOperatorFromToken_forDeny() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockServerWebExchange exchange = run(
            propsWithApprovalAuth(true, Map.of("t1", "alice")),
            MockServerHttpRequest.post("/api/customer/approvals/AP-1/deny")
                .header("X-Approval-Token", "t1").build(),
            chainCalled);
        assertTrue(chainCalled.get());
        assertEquals("alice", exchange.getAttribute(ApprovalAuthWebFilter.RESOLVED_OPERATOR_ATTR));
    }

    @Test
    void shouldNotGuard_listAndGetEndpoints() {
        // 查询类端点不受本过滤器约束（仍走通用 ApiKeyAuthWebFilter），即便鉴权开启且无 token 也放行
        AtomicBoolean chainCalled1 = new AtomicBoolean(false);
        run(propsWithApprovalAuth(true, Map.of("t1", "alice")),
            MockServerHttpRequest.get("/api/customer/approvals").build(), chainCalled1);
        assertTrue(chainCalled1.get(), "GET 列表不应被本过滤器拦截");

        AtomicBoolean chainCalled2 = new AtomicBoolean(false);
        run(propsWithApprovalAuth(true, Map.of("t1", "alice")),
            MockServerHttpRequest.get("/api/customer/approvals/AP-1").build(), chainCalled2);
        assertTrue(chainCalled2.get(), "GET 详情不应被本过滤器拦截");
    }

    @Test
    void shouldNotGuard_unrelatedPaths() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        run(propsWithApprovalAuth(true, Map.of("t1", "alice")),
            MockServerHttpRequest.post("/api/customer/chat").build(), chainCalled);
        assertTrue(chainCalled.get(), "无关路径不应被拦截");
    }
}
