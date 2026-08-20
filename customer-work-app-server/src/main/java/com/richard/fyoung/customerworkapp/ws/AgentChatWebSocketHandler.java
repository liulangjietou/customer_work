package com.richard.fyoung.customerworkapp.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential.AgentIdentity;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerworkapp.chat.ChatDispatchService;
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
 * 坐席对话 WebSocket 处理器（映射 {@code /ws/agent}）。
 *
 * <p>与 {@link UserChatWebSocketHandler} 同构，仅握手校验改用 {@link AgentAccessCredential} 校验 HMAC 令牌，
 * 入站 {@code chat} 帧的 data 为 {@code {ticketId, content}}，交 {@link ChatDispatchService#onAgentMessage} 分发。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentChatWebSocketHandler.class);

    private final CustomerWorkProperties properties;
    private final ChatDispatchService dispatch;
    private final WsSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public AgentChatWebSocketHandler(CustomerWorkProperties properties,
                                     ChatDispatchService dispatch,
                                     WsSessionRegistry registry,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.dispatch = dispatch;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Optional<AgentIdentity> identity = tokenOf(session).flatMap(token ->
            AgentAccessCredential.verifyIdentity(
                token, properties.getAgentAccess().getSecret(), System.currentTimeMillis()));
        if (identity.isEmpty()) {
            log.info("ws agent handshake rejected: invalid token");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        AgentIdentity authenticated = identity.get();
        String tenantId = authenticated.tenantId();
        if (properties.getTenant().isEnabled() && (tenantId == null || tenantId.isBlank())) {
            log.info("ws agent handshake rejected: token tenant missing");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        String effectiveTenant = properties.getTenant().isEnabled() ? tenantId : TenantContext.DEFAULT;
        return handleAuthenticated(session, authenticated.agentId(), effectiveTenant)
            .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, effectiveTenant));
    }

    private Mono<Void> handleAuthenticated(WebSocketSession session, String agent, String tenantId) {
        return Mono.defer(() -> TenantContext.callWith(tenantId, () -> {
            Sinks.Many<String> sink = registry.registerAgent(agent);

            Flux<WebSocketMessage> outbound = sink.asFlux().map(session::textMessage);
            Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(payload -> handleInbound(agent, payload)
                    .onErrorResume(e -> {
                        log.error("ws agent inbound failed, code={}, agent={}",
                            "WS-AGENT-INBOUND-FAIL", agent, e);
                        registry.pushToAgent(agent,
                            WsFrame.error("CHAT-FRAME-INVALID", "消息格式错误或处理失败"));
                        return Mono.empty();
                    }))
                .then();

            return session.send(outbound)
                .and(receive)
                .doFinally(signal -> TenantContext.runWith(tenantId,
                    () -> registry.unregisterAgent(agent, sink)));
        }));
    }

    private Mono<Void> handleInbound(String agent, String payload) {
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
                return dispatch.onAgentMessage(agent, text(data, WsFrame.KEY_TICKET_ID), text(data, WsFrame.KEY_CONTENT));
            case WsFrame.TYPE_PING:
                registry.pushToAgent(agent, WsFrame.pong());
                return Mono.empty();
            default:
                registry.pushToAgent(agent, WsFrame.error("CHAT-FRAME-UNKNOWN-TYPE", "unknown frame type: " + type));
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
