package com.richard.fyoung.customerworkapp.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.chat.ChatDispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
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
import java.util.Optional;
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

class UserChatWebSocketHandlerTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void connectionLifecycleShouldUseTokenTenantWithoutLeakingSubscriberThread() {
        UserJwtService jwtService = mock(UserJwtService.class);
        ChatDispatchService dispatch = mock(ChatDispatchService.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        UserPrincipal principal = new UserPrincipal("user-1", "alice", "Alice", "tenant-a");
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<String> registrationTenant = new AtomicReference<>();

        when(jwtService.verify("jwt-token")).thenReturn(Optional.of(principal));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/user?token=jwt-token"));
        when(session.receive()).thenReturn(Flux.never());
        when(session.send(any())).thenReturn(Mono.never());
        when(registry.registerUser("user-1")).thenAnswer(invocation -> {
            registrationTenant.set(TenantContext.get());
            return sink;
        });

        UserChatWebSocketHandler handler = new UserChatWebSocketHandler(
            jwtService, dispatch, registry, new ObjectMapper());
        Disposable connection = handler.handle(session).subscribe();

        assertEquals("tenant-a", registrationTenant.get());
        assertNull(TenantContext.get());

        connection.dispose();
        assertNull(TenantContext.get());
        verify(registry).unregisterUser("user-1", sink);
    }

    @Test
    void legacyTenantTokenShouldCloseWithPolicyViolation() {
        UserJwtService jwtService = mock(UserJwtService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        UserPrincipal principal = new UserPrincipal("user-1", "alice", "Alice", "__platform__");
        when(jwtService.verify("jwt-token")).thenReturn(Optional.of(principal));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/user?token=jwt-token"));
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());

        UserChatWebSocketHandler handler = new UserChatWebSocketHandler(
            jwtService, mock(ChatDispatchService.class), mock(WsSessionRegistry.class), new ObjectMapper());

        handler.handle(session).block();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void revokedTenantEpochShouldCloseHandshakeWithPolicyViolation() {
        UserJwtService jwtService = mock(UserJwtService.class);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        WebSocketSession session = mock(WebSocketSession.class);
        UserPrincipal principal = new UserPrincipal(
            "user-1", "alice", "Alice", "tenant-a", 4L);
        when(jwtService.verify("jwt-token")).thenReturn(Optional.of(principal));
        when(accessGuard.check("tenant-a", 4L, true)).thenReturn(
            new TenantAccessDecision(TenantAccessDecision.Kind.CREDENTIAL_REVOKED, 5L));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/user?token=jwt-token"));
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());

        UserChatWebSocketHandler handler = new UserChatWebSocketHandler(
            jwtService, mock(ChatDispatchService.class), mock(WsSessionRegistry.class),
            new ObjectMapper(), accessGuard);

        handler.handle(session).block();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void tenantRestrictedBetweenHandshakeAndRegistrationShouldCloseConnection() {
        UserJwtService jwtService = mock(UserJwtService.class);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        UserPrincipal principal = new UserPrincipal("user-1", "alice", "Alice", "tenant-a", 4L);
        when(jwtService.verify("jwt-token")).thenReturn(Optional.of(principal));
        when(accessGuard.check("tenant-a", 4L, true)).thenReturn(TenantAccessDecision.allowed(4L));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/user?token=jwt-token"));
        when(registry.registerUser("user-1"))
            .thenReturn(Sinks.many().unicast().onBackpressureBuffer());
        when(registry.isTenantRestricted("tenant-a")).thenReturn(true);
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());

        UserChatWebSocketHandler handler = new UserChatWebSocketHandler(
            jwtService, mock(ChatDispatchService.class), registry, new ObjectMapper(), accessGuard);

        handler.handle(session).block();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void accessEpochChangedDuringRegistrationShouldUnregisterOldJwtConnection() {
        UserJwtService jwtService = mock(UserJwtService.class);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        UserPrincipal principal = new UserPrincipal("user-1", "alice", "Alice", "tenant-a", 4L);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean epochChanged = new AtomicBoolean();
        when(jwtService.verify("jwt-token")).thenReturn(Optional.of(principal));
        when(accessGuard.check("tenant-a", 4L, true)).thenAnswer(invocation -> epochChanged.get()
            ? new TenantAccessDecision(TenantAccessDecision.Kind.CREDENTIAL_REVOKED, 5L)
            : TenantAccessDecision.allowed(4L));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/user?token=jwt-token"));
        when(registry.registerUser("user-1")).thenAnswer(invocation -> {
            epochChanged.set(true);
            return sink;
        });
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());
        UserChatWebSocketHandler handler = new UserChatWebSocketHandler(
            jwtService, mock(ChatDispatchService.class), registry, new ObjectMapper(), accessGuard);

        handler.handle(session).block();

        verify(accessGuard, times(3)).check("tenant-a", 4L, true);
        verify(registry).unregisterUser("user-1", sink);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void tenantDisconnectSignalShouldTerminateReceiveSideOfExistingConnection() {
        UserJwtService jwtService = mock(UserJwtService.class);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        WsSessionRegistry registry = new WsSessionRegistry(new ObjectMapper());
        WebSocketSession session = mock(WebSocketSession.class);
        UserPrincipal principal = new UserPrincipal("user-1", "alice", "Alice", "tenant-a", 4L);
        when(jwtService.verify("jwt-token")).thenReturn(Optional.of(principal));
        when(accessGuard.check("tenant-a", 4L, true)).thenReturn(TenantAccessDecision.allowed(4L));
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/user?token=jwt-token"));
        when(session.receive()).thenReturn(Flux.never());
        when(session.send(any())).thenAnswer(invocation -> {
            Publisher<WebSocketMessage> outbound = invocation.getArgument(0);
            return Flux.from(outbound).then();
        });

        UserChatWebSocketHandler handler = new UserChatWebSocketHandler(
            jwtService, mock(ChatDispatchService.class), registry, new ObjectMapper(), accessGuard);

        StepVerifier.create(handler.handle(session))
            .then(() -> registry.disconnectTenant("tenant-a"))
            .verifyComplete();

        assertEquals(0, registry.onlineUsers());
    }

    @Test
    void accessRevokedAfterFirstFrameShouldRejectNextFrameAndCloseConnection() {
        UserJwtService jwtService = mock(UserJwtService.class);
        TenantAccessGuard accessGuard = mock(TenantAccessGuard.class);
        ChatDispatchService dispatch = mock(ChatDispatchService.class);
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        UserPrincipal principal = new UserPrincipal("user-1", "alice", "Alice", "tenant-a", 4L);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean revoked = new AtomicBoolean();
        WebSocketMessage firstFrame = textFrame(
            "{\"type\":\"chat\",\"data\":{\"sessionId\":\"session-1\",\"content\":\"first\"}}");
        WebSocketMessage secondFrame = textFrame(
            "{\"type\":\"chat\",\"data\":{\"sessionId\":\"session-2\",\"content\":\"second\"}}");

        when(jwtService.verify("jwt-token")).thenReturn(Optional.of(principal));
        when(accessGuard.check("tenant-a", 4L, true)).thenAnswer(invocation -> revoked.get()
            ? new TenantAccessDecision(TenantAccessDecision.Kind.CREDENTIAL_REVOKED, 5L)
            : TenantAccessDecision.allowed(4L));
        when(dispatch.onUserMessage(principal, "session-1", "first")).thenAnswer(invocation -> {
            revoked.set(true);
            return Mono.empty();
        });
        when(session.getHandshakeInfo()).thenReturn(handshake("/ws/user?token=jwt-token"));
        when(session.receive()).thenReturn(Flux.just(firstFrame, secondFrame));
        when(session.send(any())).thenReturn(Mono.never());
        when(session.close(CloseStatus.POLICY_VIOLATION)).thenReturn(Mono.empty());
        when(registry.registerUser("user-1")).thenReturn(sink);

        UserChatWebSocketHandler handler = new UserChatWebSocketHandler(
            jwtService, dispatch, registry, new ObjectMapper(), accessGuard);

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(dispatch).onUserMessage(principal, "session-1", "first");
        verifyNoMoreInteractions(dispatch);
        verify(accessGuard, times(5)).check("tenant-a", 4L, true);
        verify(registry).disconnectTenant("tenant-a");
        verify(registry, never()).pushToUser(any(), any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(registry).unregisterUser("user-1", sink);
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
