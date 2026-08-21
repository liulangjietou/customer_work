package com.richard.fyoung.customerworkapp.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerworkapp.chat.ChatDispatchService;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Optional;

/**
 * 用户对话 WebSocket 处理器（映射 {@code /ws/user}）。
 *
 * <p>握手时从 URL 查询参数 {@code token} 取登录态 JWT 校验，失败即以 {@code POLICY_VIOLATION} 关闭；
 * 成功登记下行 Sink，出站为其 {@code asFlux()}，入站逐帧解析后交 {@link ChatDispatchService} 分发。
 * 入站单帧异常经 {@code onErrorResume} 回一帧 error，<b>不断连接</b>；连接关闭时注销登记，被 {@code concatMap}
 * 串联的在途 AI 流式订阅随之取消，无泄漏。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class UserChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UserChatWebSocketHandler.class);

    private static final String FIELD_REASON = "reason";

    private final UserJwtService jwtService;
    private final ChatDispatchService dispatch;
    private final WsSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public UserChatWebSocketHandler(UserJwtService jwtService,
                                    ChatDispatchService dispatch,
                                    WsSessionRegistry registry,
                                    ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.dispatch = dispatch;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Optional<UserPrincipal> principal = tokenOf(session).flatMap(jwtService::verify);
        if (principal.isEmpty()) {
            log.info("ws user handshake rejected: invalid token");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        UserPrincipal user = principal.get();
        if (user.tenantId() == null || user.tenantId().isBlank()) {
            log.info("ws user handshake rejected: token tenant missing");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        if (!TenantContext.isValidTenantId(user.tenantId())) {
            log.info("ws user handshake rejected: token tenant invalid");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        return handleAuthenticated(session, user)
            .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, user.tenantId()));
    }

    private Mono<Void> handleAuthenticated(WebSocketSession session, UserPrincipal user) {
        return Mono.defer(() -> TenantContext.callWith(user.tenantId(), () -> {
            Sinks.Many<String> sink = registry.registerUser(user.userId());

            Flux<WebSocketMessage> outbound = sink.asFlux().map(session::textMessage);
            Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(payload -> handleInbound(user, payload)
                    .onErrorResume(e -> {
                        log.error("ws user inbound failed, code={}, user={}",
                            "WS-USER-INBOUND-FAIL", user.userId(), e);
                        registry.pushToUser(user.userId(),
                            WsFrame.error("CHAT-FRAME-INVALID", "消息格式错误或处理失败"));
                        return Mono.empty();
                    }))
                .then();

            return session.send(outbound)
                .and(receive)
                .doFinally(signal -> TenantContext.runWith(user.tenantId(),
                    () -> registry.unregisterUser(user.userId(), sink)));
        }));
    }

    /** 解析并分发单条入站帧。解析异常抛出交由上层 onErrorResume 兜底。 */
    private Mono<Void> handleInbound(UserPrincipal user, String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            return Mono.error(e);
        }
        String type = root.path(WsFrame.KEY_TYPE).asText("");
        JsonNode data = root.path(WsFrame.KEY_DATA);
        switch (type) {
            case WsFrame.TYPE_CHAT:
                return dispatch.onUserMessage(user, text(data, WsFrame.KEY_SESSION_ID), text(data, WsFrame.KEY_CONTENT));
            case "handoff":
                return dispatch.requestHandoff(user, text(data, WsFrame.KEY_SESSION_ID), text(data, FIELD_REASON));
            case WsFrame.TYPE_PING:
                registry.pushToUser(user.userId(), WsFrame.pong());
                return Mono.empty();
            default:
                registry.pushToUser(user.userId(), WsFrame.error("CHAT-FRAME-UNKNOWN-TYPE", "unknown frame type: " + type));
                return Mono.empty();
        }
    }

    private static String text(JsonNode data, String field) {
        JsonNode node = data.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private Optional<String> tokenOf(WebSocketSession session) {
        return Optional.ofNullable(UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri())
            .build().getQueryParams().getFirst(WsConstants.QUERY_TOKEN));
    }
}
