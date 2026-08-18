package com.richard.fyoung.customerworkapp.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.chat.ChatDispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatWebSocketHandlerTest {

    private static final String SECRET = "agent-websocket-test-secret";

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void connectionLifecycleShouldUseSignedTenantWithoutLeakingSubscriberThread() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(SECRET);
        ChatDispatchService dispatch = mock(ChatDispatchService.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<String> registrationTenant = new AtomicReference<>();
        String token = AgentAccessCredential.sign(
            "agent-1", "tenant-a", System.currentTimeMillis() + 60_000L, SECRET);

        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/agent?token=" + token));
        when(session.receive()).thenReturn(Flux.never());
        when(session.send(any())).thenReturn(Mono.empty());
        when(registry.registerAgent("agent-1")).thenAnswer(invocation -> {
            registrationTenant.set(TenantContext.get());
            return sink;
        });

        AgentChatWebSocketHandler handler = new AgentChatWebSocketHandler(
            properties, dispatch, registry, new ObjectMapper());
        Disposable connection = handler.handle(session).subscribe();

        assertEquals("tenant-a", registrationTenant.get());
        assertNull(TenantContext.get());

        connection.dispose();
        assertNull(TenantContext.get());
        verify(registry).unregisterAgent("agent-1", sink);
    }

    private static HandshakeInfo handshake(String path) {
        return new HandshakeInfo(URI.create("http://localhost" + path),
            HttpHeaders.EMPTY, Mono.empty(), null);
    }
}
