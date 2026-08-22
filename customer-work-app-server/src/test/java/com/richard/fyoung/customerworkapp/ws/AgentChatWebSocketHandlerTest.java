package com.richard.fyoung.customerworkapp.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.chat.ChatDispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        when(session.send(any())).thenReturn(Mono.never());
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

    @Test
    void legacyTenantTokenShouldCloseWithPolicyViolation() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(SECRET);
        WebSocketSession session = mock(WebSocketSession.class);
        String token = AgentAccessCredential.sign(
            "agent-1", "__platform__", System.currentTimeMillis() + 60_000L, SECRET);
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/agent?token=" + token));
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());

        AgentChatWebSocketHandler handler = new AgentChatWebSocketHandler(
            properties, mock(ChatDispatchService.class), mock(WsSessionRegistry.class), new ObjectMapper());

        handler.handle(session).block();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void frozenTenantShouldCloseAgentHandshakeWithPolicyViolation() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(SECRET);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        WebSocketSession session = mock(WebSocketSession.class);
        String token = AgentAccessCredential.sign(
            "agent-1", "tenant-a", System.currentTimeMillis() + 60_000L, SECRET);
        when(accessGuard.check("tenant-a", null, false)).thenReturn(
            new TenantAccessDecision(TenantAccessDecision.Kind.ACCESS_DENIED, 2L));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/agent?token=" + token));
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());

        AgentChatWebSocketHandler handler = new AgentChatWebSocketHandler(
            properties, mock(ChatDispatchService.class), mock(WsSessionRegistry.class),
            new ObjectMapper(), accessGuard);

        handler.handle(session).block();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void tenantRestrictedBetweenHandshakeAndRegistrationShouldCloseConnection() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(SECRET);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        String token = AgentAccessCredential.sign(
            "agent-1", "tenant-a", System.currentTimeMillis() + 60_000L, SECRET);
        when(accessGuard.check("tenant-a", null, false)).thenReturn(TenantAccessDecision.allowed(4L));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/agent?token=" + token));
        when(registry.registerAgent("agent-1"))
            .thenReturn(Sinks.many().unicast().onBackpressureBuffer());
        when(registry.isTenantRestricted("tenant-a")).thenReturn(true);
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());

        AgentChatWebSocketHandler handler = new AgentChatWebSocketHandler(
            properties, mock(ChatDispatchService.class), registry, new ObjectMapper(), accessGuard);

        handler.handle(session).block();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void accessEpochChangedDuringRegistrationShouldUnregisterOldAgentConnection() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(SECRET);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean epochChanged = new AtomicBoolean();
        String token = AgentAccessCredential.sign(
            "agent-1", "tenant-a", System.currentTimeMillis() + 60_000L, SECRET);
        when(accessGuard.check("tenant-a", null, false)).thenAnswer(invocation -> epochChanged.get()
            ? TenantAccessDecision.allowed(5L)
            : TenantAccessDecision.allowed(4L));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/agent?token=" + token));
        when(registry.registerAgent("agent-1")).thenAnswer(invocation -> {
            epochChanged.set(true);
            return sink;
        });
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());
        AgentChatWebSocketHandler handler = new AgentChatWebSocketHandler(
            properties, mock(ChatDispatchService.class), registry, new ObjectMapper(), accessGuard);

        handler.handle(session).block();

        verify(accessGuard, times(3)).check("tenant-a", null, false);
        verify(registry).unregisterAgent("agent-1", sink);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void tenantFrozenAfterFirstFrameShouldRejectNextFrameAndCloseConnection() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(SECRET);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        ChatDispatchService dispatch = mock(ChatDispatchService.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean frozen = new AtomicBoolean();
        String token = AgentAccessCredential.sign(
            "agent-1", "tenant-a", System.currentTimeMillis() + 60_000L, SECRET);
        WebSocketMessage firstFrame = textFrame(
            "{\"type\":\"chat\",\"data\":{\"ticketId\":\"ticket-1\",\"content\":\"first\"}}");
        WebSocketMessage secondFrame = textFrame(
            "{\"type\":\"chat\",\"data\":{\"ticketId\":\"ticket-2\",\"content\":\"second\"}}");

        when(accessGuard.check("tenant-a", null, false)).thenAnswer(invocation -> frozen.get()
            ? new TenantAccessDecision(TenantAccessDecision.Kind.ACCESS_DENIED, 5L)
            : TenantAccessDecision.allowed(4L));
        when(dispatch.onAgentMessage("agent-1", "ticket-1", "first")).thenAnswer(invocation -> {
            frozen.set(true);
            return Mono.empty();
        });
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/agent?token=" + token));
        when(session.receive()).thenReturn(Flux.just(firstFrame, secondFrame));
        when(session.send(any())).thenReturn(Mono.never());
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());
        when(registry.registerAgent("agent-1")).thenReturn(sink);

        AgentChatWebSocketHandler handler = new AgentChatWebSocketHandler(
            properties, dispatch, registry, new ObjectMapper(), accessGuard);

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(dispatch).onAgentMessage("agent-1", "ticket-1", "first");
        verifyNoMoreInteractions(dispatch);
        verify(accessGuard, times(5)).check("tenant-a", null, false);
        verify(registry).disconnectTenant("tenant-a");
        verify(registry, never()).pushToAgent(any(), any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(registry).unregisterAgent("agent-1", sink);
    }

    private static WebSocketMessage textFrame(String payload) {
        WebSocketMessage message = mock(WebSocketMessage.class);
        when(message.getPayloadAsText()).thenReturn(payload);
        return message;
    }

    private static HandshakeInfo handshake(String path) {
        return new HandshakeInfo(URI.create("http://localhost" + path),
            HttpHeaders.EMPTY, Mono.empty(), null);
    }
}
