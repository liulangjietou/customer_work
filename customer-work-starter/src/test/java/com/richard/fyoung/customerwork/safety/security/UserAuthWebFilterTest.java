package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
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
}
