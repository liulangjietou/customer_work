package com.richard.fyoung.customerworkapp.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential.AgentIdentity;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerworkapp.chat.ChatDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TenantAccessGuard tenantAccessGuard;

    public AgentChatWebSocketHandler(CustomerWorkProperties properties,
                                     ChatDispatchService dispatch,
                                     WsSessionRegistry registry,
                                     ObjectMapper objectMapper) {
        this(properties, dispatch, registry, objectMapper, null);
    }

    @Autowired
    public AgentChatWebSocketHandler(CustomerWorkProperties properties,
                                     ChatDispatchService dispatch,
                                     WsSessionRegistry registry,
                                     ObjectMapper objectMapper,
                                     TenantAccessGuard tenantAccessGuard) {
        this.properties = properties;
        this.dispatch = dispatch;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.tenantAccessGuard = tenantAccessGuard;
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
        if (properties.getTenant().isEnabled() && !TenantContext.isValidTenantId(tenantId)) {
            log.info("ws agent handshake rejected: token tenant invalid");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        String effectiveTenant = properties.getTenant().isEnabled() ? tenantId : TenantContext.DEFAULT;
        TenantAccessDecision access = currentAccess(effectiveTenant);
        if (!access.isAllowed()) {
            log.info("ws agent handshake rejected: code={}, tenantId={}", access.code(), effectiveTenant);
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        return handleAuthenticated(session, authenticated.agentId(), effectiveTenant, access.accessEpoch())
            .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, effectiveTenant));
    }

    private Mono<Void> handleAuthenticated(WebSocketSession session, String agent, String tenantId,
                                           long handshakeAccessEpoch) {
        return Mono.defer(() -> TenantContext.callWith(tenantId, () -> {
            TenantAccessDecision beforeRegistration = currentAccess(tenantId);
            if (!sameAllowedEpoch(beforeRegistration, handshakeAccessEpoch)) {
                log.info("ws agent registration rejected before register: code={}, tenantId={}",
                    beforeRegistration.code(), tenantId);
                return session.close(CloseStatus.POLICY_VIOLATION);
            }
            Sinks.Many<String> sink = registry.registerAgent(agent);
            TenantAccessDecision afterRegistration = currentAccess(tenantId);
            // 坐席令牌不携带 epoch，因此必须把握手时确认的 epoch 带入登记前后双检。
            if (!sameAllowedEpoch(afterRegistration, handshakeAccessEpoch)
                || registry.isTenantRestricted(tenantId)) {
                registry.unregisterAgent(agent, sink);
                log.info("ws agent registration rejected after tenant access change: code={}, tenantId={}",
                    afterRegistration.code(), tenantId);
                return session.close(CloseStatus.POLICY_VIOLATION);
            }

            Flux<WebSocketMessage> outbound = sink.asFlux().map(session::textMessage);
            Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(payload -> handleAuthorizedInbound(session, agent, tenantId, payload))
                .then();

            // 任一方向结束就取消另一侧：撤权完成出站 Sink 后，不能继续等待客户端 receive 无限存活。
            return Mono.firstWithSignal(session.send(outbound), receive)
                .doFinally(signal -> TenantContext.runWith(tenantId,
                    () -> registry.unregisterAgent(agent, sink)));
        }));
    }

    private boolean sameAllowedEpoch(TenantAccessDecision decision, long expectedEpoch) {
        return decision.isAllowed() && decision.accessEpoch() == expectedEpoch;
    }

    private Mono<Void> handleAuthorizedInbound(WebSocketSession session,
                                               String agent,
                                               String tenantId,
                                               String payload) {
        TenantAccessDecision access = currentAccess(tenantId);
        if (!access.isAllowed()) {
            log.info("ws agent connection revoked: code={}, tenantId={}", access.code(), tenantId);
            registry.disconnectTenant(tenantId);
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        return handleInbound(agent, payload)
            .onErrorResume(e -> {
                log.error("ws agent inbound failed, code={}, agent={}",
                    "WS-AGENT-INBOUND-FAIL", agent, e);
                registry.pushToAgent(agent,
                    WsFrame.error("CHAT-FRAME-INVALID", "消息格式错误或处理失败"));
                return Mono.empty();
            });
    }

    private TenantAccessDecision currentAccess(String tenantId) {
        return tenantAccessGuard == null
            ? TenantAccessDecision.allowed(0L)
            : tenantAccessGuard.check(tenantId, null, false);
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
