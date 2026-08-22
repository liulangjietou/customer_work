package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户 JWT 过滤器单测：非 /user 放行、无/错 token 401、合法 token 放入主体。
 * @author owlzhangfq@gmail.com
 */
class UserAuthWebFilterTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
        QuotaSubjectContext.clear();
    }

    private UserJwtService jwtService() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getUserAuth().setJwtSecret("filter-test-secret");
        return new UserJwtService(props);
    }

    private WebFilterChain recordingChain(AtomicBoolean invoked) {
        return exchange -> {
            invoked.set(true);
            return Mono.empty();
        };
    }

    @Test
    void nonUserPath_shouldPassThrough() {
        UserAuthWebFilter filter = new UserAuthWebFilter(jwtService());
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/auth/login"));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertTrue(invoked.get(), "非 /user 路径应直接放行");
    }

    @Test
    void browserProtectedPaths_shouldRequireJwt() {
        UserAuthWebFilter filter = new UserAuthWebFilter(jwtService());
        String[] paths = {
            "/api/customer/auth/me",
            "/api/customer/auth/avatar",
            "/api/customer/attachment",
            "/api/customer/feedback",
            "/api/customer/csat/session-1"
        };

        for (String path : paths) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            AtomicBoolean invoked = new AtomicBoolean(false);
            filter.filter(exchange, recordingChain(invoked)).block();
            assertFalse(invoked.get(), path + " 无 JWT 不应放行");
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void publicAndOperationsPaths_shouldNotBeClaimedByUserJwtFilter() {
        UserAuthWebFilter filter = new UserAuthWebFilter(jwtService());
        String[] paths = {
            "/api/customer/auth/login",
            "/api/customer/auth/register",
            "/api/customer/csat/summary",
            "/ws/user"
        };

        for (String path : paths) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            AtomicBoolean invoked = new AtomicBoolean(false);
            filter.filter(exchange, recordingChain(invoked)).block();
            assertTrue(invoked.get(), path + " 应交给对应的鉴权机制");
        }
    }

    @Test
    void userPath_missingToken_shouldReturn401() {
        UserAuthWebFilter filter = new UserAuthWebFilter(jwtService());
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets"));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertFalse(invoked.get(), "无 token 不应放行");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void userPath_validToken_shouldSetPrincipalAndPass() {
        UserJwtService jwt = jwtService();
        UserAuthWebFilter filter = new UserAuthWebFilter(jwt);
        String token = jwt.issue("U1", "alice", "Alice");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertTrue(invoked.get());
        UserPrincipal principal = exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR);
        assertEquals("U1", principal.userId());
        assertFalse(TenantContext.isPresent(), "请求结束必须清理租户上下文");
    }

    @Test
    void validJwt_shouldAlwaysPropagateVerifiedUserSubjectEvenWhenQuotaGuardIsDisabled() {
        UserJwtService jwt = jwtService();
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        when(accessGuard.check("tenant-a", 7L, true)).thenReturn(TenantAccessDecision.allowed(7L));
        UserAuthWebFilter filter = new UserAuthWebFilter(jwt, accessGuard);
        String token = jwt.issue("U1", "alice", "Alice", "tenant-a", 7L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicBoolean threadLocalSeen = new AtomicBoolean(false);
        AtomicBoolean reactorContextSeen = new AtomicBoolean(false);
        WebFilterChain chain = ignored -> {
            assertEquals(QuotaSubject.user("U1"), QuotaSubjectContext.get());
            threadLocalSeen.set(true);
            return Mono.deferContextual(context -> {
                assertEquals(QuotaSubject.user("U1"),
                    context.get(QuotaSubjectContextThreadLocalAccessor.KEY));
                reactorContextSeen.set(true);
                return Mono.empty();
            });
        };

        filter.filter(exchange, chain).block();

        assertTrue(threadLocalSeen.get());
        assertTrue(reactorContextSeen.get());
        assertFalse(QuotaSubjectContext.isPresent(), "请求结束必须恢复主体上下文");
    }

    @Test
    void revokedTenantEpoch_shouldReturnObservable401WithoutCallingChain() {
        UserJwtService jwt = jwtService();
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        when(accessGuard.check("tenant-a", 7L, true)).thenReturn(
            new TenantAccessDecision(TenantAccessDecision.Kind.CREDENTIAL_REVOKED, 8L));
        UserAuthWebFilter filter = new UserAuthWebFilter(jwt, accessGuard);
        String token = jwt.issue("U1", "alice", "Alice", "tenant-a", 7L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        assertTrue(body.contains("TENANT_CREDENTIAL_REVOKED"));
    }

    @Test
    void existingDifferentTenant_shouldReturn403WithoutCallingChain() {
        UserJwtService jwt = jwtService();
        UserAuthWebFilter filter = new UserAuthWebFilter(jwt);
        String token = jwt.issue("U1", "alice", "Alice", "tenant-a");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicBoolean invoked = new AtomicBoolean(false);
        TenantContext.set("tenant-b");

        filter.filter(exchange, recordingChain(invoked)).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertNull(exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR));
        assertEquals("tenant-b", TenantContext.get(), "过滤器不能覆盖上游建立的租户上下文");
    }

    @Test
    void userPath_invalidToken_shouldReturn401() {
        UserAuthWebFilter filter = new UserAuthWebFilter(jwtService());
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage.token.value"));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR));
    }

    @Test
    void userPath_signedTokenWithInvalidTenant_shouldReturn401() {
        UserJwtService jwt = jwtService();
        UserAuthWebFilter filter = new UserAuthWebFilter(jwt);
        String token = jwt.issue("U1", "alice", "Alice", "_legacy");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR));
    }
}
