package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * API Key 鉴权过滤器单测（接入层安全）。
 * @author owlzhangfq@gmail.com
 */
class ApiKeyAuthWebFilterTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private CustomerWorkProperties propsWithAuth(boolean enabled, String... keys) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSecurity().getAuth().setEnabled(enabled);
        props.getSecurity().getAuth().setApiKeys(List.of(keys));
        return props;
    }

    private boolean runFilter(CustomerWorkProperties props, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };
        new ApiKeyAuthWebFilter(props).filter(exchange, chain).block();
        return chainCalled.get();
    }

    @Test
    void shouldPass_whenAuthDisabled() {
        boolean passed = runFilter(propsWithAuth(false),
            MockServerHttpRequest.post("/api/customer/chat").build());
        assertTrue(passed, "鉴权关闭时应放行");
    }

    @Test
    void shouldReject_whenEnabledAndNoKey() {
        CustomerWorkProperties props = propsWithAuth(true, "secret-key");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        new ApiKeyAuthWebFilter(props).filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertFalse(chainCalled.get(), "无 Key 不应放行");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldPass_whenValidKey() {
        boolean passed = runFilter(propsWithAuth(true, "secret-key"),
            MockServerHttpRequest.post("/api/customer/chat").header("X-API-Key", "secret-key").build());
        assertTrue(passed, "合法 Key 应放行");
    }

    @Test
    void shouldExemptHealthAndActuator() {
        CustomerWorkProperties props = propsWithAuth(true, "secret-key");
        assertTrue(runFilter(props, MockServerHttpRequest.get("/api/customer/health").build()),
            "健康检查应免鉴权");
        assertTrue(runFilter(props, MockServerHttpRequest.get("/actuator/health").build()),
            "Actuator 应免鉴权");
    }

    @Test
    void shouldBypassApiKeyForBrowserJwtAndWebSocketRoutes() {
        CustomerWorkProperties props = propsWithAuth(true, "service-key");

        assertTrue(runFilter(props, MockServerHttpRequest.post("/api/customer/auth/login").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.get("/api/customer/auth/me").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.get("/api/customer/user/tickets").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.post("/api/customer/attachment").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.post("/api/customer/feedback").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.get("/api/customer/csat/session-1").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.get("/ws/user?token=jwt").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.get("/ws/agent?token=agent").build()));
    }

    @Test
    void shouldBypassApiKeyForAgentCredentialRoutes() {
        CustomerWorkProperties props = propsWithAuth(true, "service-key");

        assertTrue(runFilter(props, MockServerHttpRequest.get("/api/customer/agent/tickets").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.post("/api/customer/handoffs/claim").build()));
    }

    @Test
    void shouldStillRequireApiKeyForOperationsAndPartnerRoutes() {
        CustomerWorkProperties props = propsWithAuth(true, "service-key");

        assertFalse(runFilter(props, MockServerHttpRequest.get("/api/customer/csat/summary").build()));
        assertFalse(runFilter(props, MockServerHttpRequest.post("/api/customer/chat").build()));
        assertTrue(runFilter(props, MockServerHttpRequest.get("/api/customer/csat/summary")
            .header("X-API-Key", "service-key").build()));
    }

    /** P11 修复（常量时间比较）后，配置多个 Key 时命中其中任意一个仍应放行。 */
    @Test
    void shouldPass_whenMatchesOneOfMultipleKeys() {
        boolean passed = runFilter(propsWithAuth(true, "k1", "k2", "k3"),
            MockServerHttpRequest.post("/api/customer/chat").header("X-API-Key", "k2").build());
        assertTrue(passed, "命中多 Key 之一应放行");
    }

    /** P11：等长但不相等的错误 Key 仍应拒绝（常量时间比较不改变正确性）。 */
    @Test
    void shouldReject_whenKeyWrongButSameLength() {
        CustomerWorkProperties props = propsWithAuth(true, "secret-key");
        boolean passed = runFilter(props,
            MockServerHttpRequest.post("/api/customer/chat").header("X-API-Key", "secret-keX").build());
        assertFalse(passed, "等长错误 Key 不应放行");
    }

    @Test
    void tenantKeyShouldPropagateTenantWithoutLeakingLongRequestThread() {
        CustomerWorkProperties properties = propsWithAuth(true);
        properties.getSecurity().getAuth().getTenantKeys().put("tenant-key", "tenant-a");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .header("X-API-Key", "tenant-key").build());
        AtomicReference<String> observedTenant = new AtomicReference<>();
        Mono<Void> result = new ApiKeyAuthWebFilter(properties).filter(exchange, current -> {
            observedTenant.set(TenantContext.get());
            return Mono.never();
        });

        Disposable subscription = result.subscribe();
        try {
            assertEquals("tenant-a", observedTenant.get());
            assertFalse(TenantContext.isPresent(), "长请求不能把租户 ThreadLocal 留在 EventLoop 上");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void tenantKeyWithInvalidTenant_shouldReturn401() {
        CustomerWorkProperties properties = propsWithAuth(true);
        properties.getSecurity().getAuth().getTenantKeys().put("legacy-key", "_legacy");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .header("X-API-Key", "legacy-key").build());
        AtomicBoolean invoked = new AtomicBoolean(false);

        new ApiKeyAuthWebFilter(properties).filter(exchange, current -> {
            invoked.set(true);
            return Mono.empty();
        }).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void frozenTenantKey_shouldReturnObservable403WithoutCallingChain() {
        CustomerWorkProperties properties = propsWithAuth(true);
        properties.getSecurity().getAuth().getTenantKeys().put("tenant-key", "acme");
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        when(accessGuard.check("acme", null, false)).thenReturn(
            new TenantAccessDecision(TenantAccessDecision.Kind.ACCESS_DENIED, 5L));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .header("X-API-Key", "tenant-key").build());
        AtomicBoolean invoked = new AtomicBoolean(false);

        new ApiKeyAuthWebFilter(properties, accessGuard).filter(exchange, current -> {
            invoked.set(true);
            return Mono.empty();
        }).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("TENANT_ACCESS_DENIED"));
    }
}
