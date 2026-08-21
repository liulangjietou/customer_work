package com.richard.fyoung.customerworkapp.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.chat.ChatDispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        when(session.send(any())).thenReturn(Mono.empty());
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

    private static HandshakeInfo handshake(String path) {
        return new HandshakeInfo(URI.create("http://localhost" + path),
            HttpHeaders.EMPTY, Mono.empty(), null);
    }
}
