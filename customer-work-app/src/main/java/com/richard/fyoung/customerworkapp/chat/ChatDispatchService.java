package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.service.CustomerServiceService;
import com.richard.fyoung.customerwork.ticket.Ticket;
import com.richard.fyoung.customerwork.ticket.TicketActorType;
import com.richard.fyoung.customerwork.ticket.TicketCategory;
import com.richard.fyoung.customerwork.ticket.TicketService;
import com.richard.fyoung.customerworkapp.security.UserPrincipal;
import com.richard.fyoung.customerworkapp.ws.WsFrame;
import com.richard.fyoung.customerworkapp.ws.WsSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话分发核心：把一条用户/坐席消息按工单当前状态路由到 AI 自助、人工排队提示、坐席转发或转人工。
 *
 * <p>所有落库（工单、消息）都是阻塞 IO，统一挪到 {@code boundedElastic} 线程执行，绝不占用 Netty 事件循环；
 * 下推走 {@link WsSessionRegistry}（非阻塞 Sink）。AI 流式回复的订阅寿命随返回的 {@link Mono} 绑定到 WS
 * 入站处理（连接关闭 → 入站取消 → 流式订阅一并取消），不泄漏。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChatDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ChatDispatchService.class);

    /** 会话号归属前缀模板：{@code u<userId>:}（用户建会话时按此拼装，见 UserTicketController）。 */
    private static final String SESSION_PREFIX = "u";
    private static final String SESSION_DELIMITER = ":";

    private static final int TITLE_MAX_LEN = 50;

    private static final String HANDOFF_KEYWORD_REASON = "用户关键词触发转人工";
    private static final String NOTICE_HANDOFF = "正在为您转接人工客服，请稍候";
    private static final String NOTICE_WAITING_AGENT = "客服正在赶来的路上，请稍候";
    private static final String NOTICE_WAITING_CONFIRM = "本次问题坐席已处理，请在工单中确认是否解决";
    private static final String ERR_SESSION_OWNERSHIP = "会话不属于当前用户";

    private static final String KEY_MESSAGE_ID = "messageId";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_TICKET_ID = "ticketId";
    private static final String KEY_SENDER_TYPE = "senderType";
    private static final String KEY_SENDER_ID = "senderId";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_TS = "ts";

    private final TicketService ticketService;
    private final ChatLogService chatLogService;
    private final CustomerServiceService customerServiceService;
    private final HandoffKeywordDetector keywordDetector;
    private final WsSessionRegistry registry;

    public ChatDispatchService(TicketService ticketService,
                               ChatLogService chatLogService,
                               CustomerServiceService customerServiceService,
                               HandoffKeywordDetector keywordDetector,
                               WsSessionRegistry registry) {
        this.ticketService = ticketService;
        this.chatLogService = chatLogService;
        this.customerServiceService = customerServiceService;
        this.keywordDetector = keywordDetector;
        this.registry = registry;
    }

    /** 分发动作：由工单状态与关键词共同决定。 */
    private enum Action {
        HANDOFF, AI_STREAM, WAITING_AGENT_NOTICE, FORWARD_AGENT, WAITING_CONFIRM_NOTICE
    }

    /** 准备阶段（阻塞落库）产出的决策：动作 + 相关工单快照 + 已落库的用户消息（转发坐席时带完整元数据）。 */
    private record Decision(Action action, Ticket ticket, ChatMessage userMessage) {
    }

    /**
     * 处理一条用户消息：校验会话归属 → 定位/新建工单 → 落库 → 关键词/状态路由。
     *
     * @return 处理完成信号（含 AI 流式全过程，寿命绑定调用方订阅）
     */
    public Mono<Void> onUserMessage(UserPrincipal user, String sessionId, String content) {
        if (!ownsSession(user.userId(), sessionId)) {
            registry.pushToUser(user.userId(), WsFrame.error("CHAT-SESSION-DENIED", ERR_SESSION_OWNERSHIP));
            return Mono.empty();
        }
        return Mono.fromCallable(() -> prepare(user, sessionId, content))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(decision -> act(user.userId(), sessionId, content, decision))
            .onErrorResume(e -> {
                log.error("[session {}] user message dispatch failed, code={}", sessionId,
                    "CHAT-USER-DISPATCH-FAIL", e);
                registry.pushToUser(user.userId(), WsFrame.error("CHAT-USER-DISPATCH-FAIL", "消息处理失败，请稍后再试"));
                return Mono.empty();
            });
    }

    /**
     * 用户在 WS 内主动请求转人工（type=handoff）：与关键词命中同路径。
     */
    public Mono<Void> requestHandoff(UserPrincipal user, String sessionId, String reason) {
        if (!ownsSession(user.userId(), sessionId)) {
            registry.pushToUser(user.userId(), WsFrame.error("CHAT-SESSION-DENIED", ERR_SESSION_OWNERSHIP));
            return Mono.empty();
        }
        String finalReason = (reason == null || reason.isBlank()) ? HANDOFF_KEYWORD_REASON : reason;
        return Mono.fromRunnable(() -> {
                ticketService.requestHandoff(sessionId, finalReason, TicketActorType.USER, user.userId());
                registry.pushToUser(user.userId(), WsFrame.system(NOTICE_HANDOFF));
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.error("[session {}] handoff request failed, code={}", sessionId, "CHAT-HANDOFF-FAIL", e);
                registry.pushToUser(user.userId(), WsFrame.error("CHAT-HANDOFF-FAIL", "转人工失败：当前会话状态不支持"));
                return Mono.empty();
            })
            .then();
    }

    /**
     * 处理一条坐席消息：校验受理归属 → 落库 → 推给用户（离线只落库不报错）。
     */
    public Mono<Void> onAgentMessage(String agentId, String ticketId, String content) {
        return Mono.fromRunnable(() -> {
                Ticket ticket = ticketService.find(ticketId).orElse(null);
                if (ticket == null) {
                    registry.pushToAgent(agentId, WsFrame.error("CHAT-TICKET-NOT-FOUND", "工单不存在: " + ticketId));
                    return;
                }
                if (!agentId.equals(ticket.getAssignee())) {
                    registry.pushToAgent(agentId, WsFrame.error("CHAT-NOT-ASSIGNEE", "非本坐席受理的工单，无法回复"));
                    return;
                }
                ChatMessage agentMsg = chatLogService.append(
                    ticket.getSessionId(), ticketId, TicketActorType.AGENT, agentId, content);
                registry.pushToUser(ticket.getUserId(), WsFrame.chat(chatData(agentMsg)));
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    // ---- 内部 ----

    /** 阻塞准备阶段：定位/新建工单、落库用户消息、回填标题、关键词判定与状态路由。 */
    private Decision prepare(UserPrincipal user, String sessionId, String content) {
        Ticket ticket = ticketService.findActiveBySession(sessionId)
            .orElseGet(() -> ticketService.createForSession(sessionId, user.userId(), null, TicketCategory.CONSULT));
        String ticketId = ticket.getId();
        ChatMessage userMessage =
            chatLogService.append(sessionId, ticketId, TicketActorType.USER, user.userId(), content);
        // 首条消息前 50 字回填空标题（已有标题不覆盖）
        ticketService.fillTitle(ticketId, title(content));

        if (keywordDetector.hit(content)) {
            ticketService.requestHandoff(sessionId, HANDOFF_KEYWORD_REASON, TicketActorType.USER, user.userId());
            return new Decision(Action.HANDOFF, ticket, userMessage);
        }
        Action action = switch (ticket.getStatus()) {
            case AI_SERVING -> Action.AI_STREAM;
            case PROCESSING, ON_HOLD -> Action.FORWARD_AGENT;
            case WAITING_CONFIRM -> Action.WAITING_CONFIRM_NOTICE;
            // WAITING_AGENT 及理论兜底：均提示排队中（RESOLVED/CLOSED 已被 findActiveBySession 排除）
            default -> Action.WAITING_AGENT_NOTICE;
        };
        return new Decision(action, ticket, userMessage);
    }

    /** 决策执行：AI 流式或各类下推（非流式动作即时完成）。 */
    private Mono<Void> act(String userId, String sessionId, String content, Decision decision) {
        Ticket ticket = decision.ticket();
        switch (decision.action()) {
            case AI_STREAM:
                return streamAi(userId, sessionId, content, ticket.getId());
            case FORWARD_AGENT:
                registry.pushToAgent(ticket.getAssignee(), WsFrame.chat(chatData(decision.userMessage())));
                return Mono.empty();
            case WAITING_CONFIRM_NOTICE:
                registry.pushToUser(userId, WsFrame.system(NOTICE_WAITING_CONFIRM));
                return Mono.empty();
            case HANDOFF:
                registry.pushToUser(userId, WsFrame.system(NOTICE_HANDOFF));
                return Mono.empty();
            case WAITING_AGENT_NOTICE:
            default:
                registry.pushToUser(userId, WsFrame.system(NOTICE_WAITING_AGENT));
                return Mono.empty();
        }
    }

    /** AI 自助流式：逐增量推 chat_chunk，完成聚合落库 BOT 消息并推 chat_done。 */
    private Mono<Void> streamAi(String userId, String sessionId, String content, String ticketId) {
        StringBuilder acc = new StringBuilder();
        return customerServiceService.chatStream(sessionId, content)
            .doOnNext(chunk -> {
                acc.append(chunk);
                registry.pushToUser(userId, WsFrame.chatChunk(chunk));
            })
            .then(Mono.fromRunnable(() -> {
                ChatMessage botMsg = chatLogService.append(
                    sessionId, ticketId, TicketActorType.BOT, null, acc.toString());
                registry.pushToUser(userId,
                    WsFrame.chatDone(botMsg.messageId(), botMsg.content(), botMsg.createdAtMs()));
            }).subscribeOn(Schedulers.boundedElastic()))
            .onErrorResume(e -> {
                log.error("[session {}] ai stream dispatch failed, code={}", sessionId, "CHAT-AI-STREAM-FAIL", e);
                registry.pushToUser(userId, WsFrame.error("CHAT-AI-STREAM-FAIL", "AI 回复出错，请稍后再试"));
                return Mono.empty();
            })
            .then();
    }

    /** 会话号归属校验：必须以 {@code u<userId>:} 开头。 */
    private boolean ownsSession(String userId, String sessionId) {
        return sessionId != null && sessionId.startsWith(SESSION_PREFIX + userId + SESSION_DELIMITER);
    }

    /** 取消息前 {@value #TITLE_MAX_LEN} 字作为工单标题。 */
    private String title(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.length() <= TITLE_MAX_LEN ? trimmed : trimmed.substring(0, TITLE_MAX_LEN);
    }

    /** 由已落库消息构造 chat 帧载荷（与前端契约字段一致：messageId/sessionId/ticketId/senderType/senderId/content/ts）。 */
    private Map<String, Object> chatData(ChatMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_MESSAGE_ID, message.messageId());
        data.put(KEY_SESSION_ID, message.sessionId());
        data.put(KEY_TICKET_ID, message.ticketId());
        data.put(KEY_SENDER_TYPE, message.senderType().name());
        data.put(KEY_SENDER_ID, message.senderId());
        data.put(KEY_CONTENT, message.content());
        data.put(KEY_TS, message.createdAtMs());
        return data;
    }
}
