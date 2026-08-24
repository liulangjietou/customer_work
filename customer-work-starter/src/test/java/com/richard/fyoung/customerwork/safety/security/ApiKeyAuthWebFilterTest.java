package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SecurityProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
        QuotaSubjectContext.clear();
        AgentInvocationIdentityContext.clear();
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
        CustomerWorkProperties props = propsWithAuth(true, "secret-key");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat").header("X-API-Key", "secret-key").build());
        AtomicReference<QuotaSubject> captured = new AtomicReference<>();
        AtomicReference<AgentInvocationIdentity> identityCaptured = new AtomicReference<>();

        new ApiKeyAuthWebFilter(props).filter(exchange, ex -> {
            captured.set(QuotaSubjectContext.get());
            identityCaptured.set(AgentInvocationIdentityContext.get());
            return Mono.empty();
        }).block();

        assertEquals(QuotaSubjectType.API_KEY, captured.get().type());
        assertFalse(captured.get().id().contains("secret-key"), "主体只允许保留 API Key 指纹");
        assertEquals(QuotaSubjectType.API_KEY, identityCaptured.get().subjectType());
        assertTrue(identityCaptured.get().authenticated());
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
        assertTrue(runFilter(props,
            MockServerHttpRequest.post("/api/customer/auth/revoke-sessions").build()));
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

    @Test
    void structuredCredential_shouldAuthenticateByKeyIdAndHashWithoutPersistingSecret() {
        CustomerWorkProperties properties = propsWithAuth(true);
        properties.getSecurity().getAuth().getCredentials().add(
            credential("partner-a", "partner-secret", "tenant-a", 3L,
                Instant.parse("2030-01-01T00:00:00Z"), List.of("POST:/api/customer/**")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .header("X-API-Key-Id", "partner-a")
                .header("X-API-Key", "partner-secret").build());
        AtomicReference<ApiKeyPrincipal> captured = new AtomicReference<>();
        AtomicReference<QuotaSubject> subject = new AtomicReference<>();

        new ApiKeyAuthWebFilter(properties, null, fixedClock()).filter(exchange, current -> {
            captured.set(current.getAttribute(ApiKeyPrincipal.EXCHANGE_ATTRIBUTE));
            subject.set(QuotaSubjectContext.get());
            return Mono.empty();
        }).block();

        assertEquals("partner-a", captured.get().keyId());
        assertEquals("tenant-a", captured.get().tenantId());
        assertEquals(3L, captured.get().epoch());
        assertEquals("partner-a", subject.get().id(), "限流与审计使用稳定 keyId，不使用原始 secret");
        SecurityProperties.Credential configured = properties.getSecurity().getAuth().getCredentials().get(0);
        assertFalse(configured.getKeyHash().contains("partner-secret"));
        assertEquals(64, configured.getKeyHash().length());
    }

    @Test
    void structuredCredential_shouldDenyPathOutsideScope() {
        CustomerWorkProperties properties = propsWithAuth(true);
        properties.getSecurity().getAuth().getCredentials().add(
            credential("chat-only", "secret", "tenant-a", 1L, null,
                List.of("POST:/api/customer/chat")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/csat/summary")
                .header("X-API-Key-Id", "chat-only")
                .header("X-API-Key", "secret").build());

        new ApiKeyAuthWebFilter(properties, null, fixedClock())
            .filter(exchange, current -> Mono.empty()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void structuredCredential_shouldRejectExpiredSecret() {
        CustomerWorkProperties properties = propsWithAuth(true);
        properties.getSecurity().getAuth().getCredentials().add(
            credential("expired", "secret", "tenant-a", 1L,
                Instant.parse("2025-12-31T23:59:59Z"), List.of("*")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .header("X-API-Key-Id", "expired")
                .header("X-API-Key", "secret").build());

        new ApiKeyAuthWebFilter(properties, null, fixedClock())
            .filter(exchange, current -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void structuredCredential_rotationShouldOverlapThenRevokeOldEpoch() {
        CustomerWorkProperties properties = propsWithAuth(true);
        properties.getSecurity().getAuth().getCredentials().add(
            credential("partner-a", "old-secret", "tenant-a", 1L, null, List.of("*")));
        properties.getSecurity().getAuth().getCredentials().add(
            credential("partner-a", "new-secret", "tenant-a", 2L, null, List.of("*")));
        properties.getSecurity().getAuth().getMinimumEpochs().put("partner-a", 1L);

        assertTrue(runStructured(properties, "partner-a", "old-secret"), "轮换窗口内旧新 secret 应重叠可用");
        assertTrue(runStructured(properties, "partner-a", "new-secret"));

        properties.getSecurity().getAuth().getMinimumEpochs().put("partner-a", 2L);
        assertFalse(runStructured(properties, "partner-a", "old-secret"), "推进最小 epoch 后旧 secret 必须失效");
        assertTrue(runStructured(properties, "partner-a", "new-secret"));
    }

    @Test
    void structuredCredential_shouldNotFallBackToLegacyWhenKeyIdIsWrong() {
        CustomerWorkProperties properties = propsWithAuth(true, "legacy-secret");
        assertFalse(runStructured(properties, "unknown", "legacy-secret"),
            "提交 keyId 后必须走结构化凭据，不能借兼容列表绕过 scope/epoch");
    }

    private boolean runStructured(CustomerWorkProperties properties, String keyId, String secret) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat")
                .header("X-API-Key-Id", keyId)
                .header("X-API-Key", secret).build());
        AtomicBoolean invoked = new AtomicBoolean();
        new ApiKeyAuthWebFilter(properties, null, fixedClock()).filter(exchange, current -> {
            invoked.set(true);
            return Mono.empty();
        }).block();
        return invoked.get();
    }

    private SecurityProperties.Credential credential(String keyId, String secret, String tenantId,
                                                     long epoch, Instant expiresAt, List<String> scopes) {
        SecurityProperties.Credential credential = new SecurityProperties.Credential();
        credential.setKeyId(keyId);
        credential.setKeyHash(ApiKeySecretHasher.sha256Hex(secret));
        credential.setTenantId(tenantId);
        credential.setEpoch(epoch);
        credential.setExpiresAt(expiresAt);
        credential.setScopes(scopes);
        return credential;
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
    }
}
