package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
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

/**
 * 用户 JWT 过滤器单测：非 /user 放行、无/错 token 401、合法 token 放入主体。
 * @author owlzhangfq@gmail.com
 */
class UserAuthWebFilterTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
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
