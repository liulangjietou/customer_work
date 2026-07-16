package com.richard.fyoung.customerwork.security;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.AgentAccessCredential;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐席 HMAC 令牌过滤器单测：非 /agent 放行、无/错 token 401、合法 token 放入 agentId。
 * @author owlzhangfq@gmail.com
 */
class AgentAuthWebFilterTest {

    private static final String SECRET = "agent-filter-secret";

    private CustomerWorkProperties props() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getAgentAccess().setSecret(SECRET);
        return props;
    }

    private WebFilterChain recordingChain(AtomicBoolean invoked) {
        return exchange -> {
            invoked.set(true);
            return Mono.empty();
        };
    }

    @Test
    void nonAgentPath_shouldPassThrough() {
        AgentAuthWebFilter filter = new AgentAuthWebFilter(props());
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/user/tickets"));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertTrue(invoked.get());
    }

    @Test
    void agentPath_missingToken_shouldReturn401() {
        AgentAuthWebFilter filter = new AgentAuthWebFilter(props());
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/agent/tickets"));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void agentPath_validToken_shouldSetAgentIdAndPass() {
        AgentAuthWebFilter filter = new AgentAuthWebFilter(props());
        String token = AgentAccessCredential.sign("agent-7", System.currentTimeMillis() + 60_000, SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/agent/tickets")
                .header("X-Agent-Token", token));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertTrue(invoked.get());
        assertEquals("agent-7", exchange.getAttribute(AgentAuthWebFilter.AGENT_ID_ATTR));
    }

    @Test
    void agentPath_expiredToken_shouldReturn401() {
        AgentAuthWebFilter filter = new AgentAuthWebFilter(props());
        String token = AgentAccessCredential.sign("agent-7", System.currentTimeMillis() - 1000, SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/customer/agent/tickets")
                .header("X-Agent-Token", token));
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(invoked)).block();

        assertFalse(invoked.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }
}
