package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.core.service.ChatTurnEvent;
import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.infra.ws.InboundMessageDeduplicator;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.infra.lock.InMemorySessionLock;
import com.richard.fyoung.customerwork.infra.lock.SessionLock;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

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
    /** cw_chat_message.content 使用 MySQL TEXT，按 UTF-8 字节数控制入口。 */
    private static final int MESSAGE_CONTENT_MAX_BYTES = 65_535;
    private static final int LOCAL_LOCK_WAIT_SECONDS = 10;

    private static final String HANDOFF_KEYWORD_REASON = "用户关键词触发转人工";
    private static final String NOTICE_HANDOFF = "正在为您转接人工客服，请稍候";
    private static final String NOTICE_WAITING_AGENT = "客服正在赶来的路上，请稍候";
    private static final String NOTICE_WAITING_CONFIRM = "本次问题坐席已处理，请在工单中确认是否解决";
    private static final String ERR_SESSION_OWNERSHIP = "会话不属于当前用户";

    /** 主体配额的触发位置标识：与 HTTP 侧的路径同位，用于在命中记录里区分是哪条链路被限。 */
    private static final String QUOTA_RESOURCE_WS_CHAT = "ws:chat";

    private final TicketService ticketService;
    private final ChatLogService chatLogService;
    private final ChatTurnService chatTurnService;
    private final HandoffKeywordDetector keywordDetector;
    private final WsSessionRegistry registry;

    /**
     * 主体配额守卫：WS 是终端用户真正的发消息入口，HTTP 过滤器管不到这里。
     *
     * <p>不判的话，用户只要改走 WS 就完全绕开了限流——而 H5 前端本来就走 WS，
     * 等于这个功能对主战场不生效。</p>
     */
    private final SubjectQuotaGuard subjectQuotaGuard;
    private final SessionLock acceptanceLock;
    private final CustomerWorkTransactionExecutor transactions;

    /** 兼容内存嵌入式调用；短时去重器不再决定受理，生产装配注入现有锁和同库事务。 */
    public ChatDispatchService(TicketService ticketService,
                               ChatLogService chatLogService,
                               ChatTurnService chatTurnService,
                               HandoffKeywordDetector keywordDetector,
                               WsSessionRegistry registry,
                               SubjectQuotaGuard subjectQuotaGuard,
                               InboundMessageDeduplicator deduplicator) {
        this(ticketService, chatLogService, chatTurnService, keywordDetector, registry,
            subjectQuotaGuard, new InMemorySessionLock(LOCAL_LOCK_WAIT_SECONDS), CustomerWorkTransactionExecutor.DIRECT);
    }

    @Autowired
    public ChatDispatchService(TicketService ticketService, ChatLogService chatLogService,
                                ChatTurnService chatTurnService, HandoffKeywordDetector keywordDetector,
                                WsSessionRegistry registry, SubjectQuotaGuard subjectQuotaGuard,
                                SessionLock acceptanceLock,
                                ObjectProvider<CustomerWorkTransactionExecutor> transactions) {
        this(ticketService, chatLogService, chatTurnService, keywordDetector, registry,
            subjectQuotaGuard, acceptanceLock, transactions.getIfAvailable(() -> CustomerWorkTransactionExecutor.DIRECT));
    }

    /** 显式事务构造用于验证提交、回滚及跨实例唯一键竞争。 */
    public ChatDispatchService(TicketService ticketService, ChatLogService chatLogService,
                                ChatTurnService chatTurnService, HandoffKeywordDetector keywordDetector,
                                WsSessionRegistry registry, SubjectQuotaGuard subjectQuotaGuard,
                                SessionLock acceptanceLock, CustomerWorkTransactionExecutor transactions) {
        this.ticketService = ticketService;
        this.chatLogService = chatLogService;
        this.chatTurnService = chatTurnService;
        this.keywordDetector = keywordDetector;
        this.registry = registry;
        this.subjectQuotaGuard = subjectQuotaGuard;
        this.acceptanceLock = acceptanceLock;
        this.transactions = transactions;
    }

    /** 分发动作：由工单状态与关键词共同决定。 */
    private enum Action {
        HANDOFF, AI_STREAM, WAITING_AGENT_NOTICE, FORWARD_AGENT, WAITING_CONFIRM_NOTICE, REPLAY_RECEIPT
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
        return onUserMessage(user, sessionId, content, null);
    }

    /**
     * @param clientMsgId 客户端消息标识，重发时沿用同一个值；为空表示客户端不支持去重
     */
    public Mono<Void> onUserMessage(UserPrincipal user, String sessionId, String content,
                                    String clientMsgId) {
        if (!ownsSession(user.userId(), sessionId)) {
            TenantContext.runWith(tenantOf(user), () -> registry.pushToUser(user.userId(),
                WsFrame.messageError("CHAT-SESSION-DENIED", ERR_SESSION_OWNERSHIP,
                    sessionId, clientMsgId, WsFrame.ACCEPTANCE_REJECTED)));
            return Mono.empty();
        }
        QuotaSubject subject = QuotaSubject.user(user.userId());
        AgentInvocationIdentity invocationIdentity = new AgentInvocationIdentity(
            tenantOf(user), subject.type(), subject.id(), true, user.accessEpoch())
            .withChannel(AgentInvocationIdentity.CHANNEL_USER_WS);
        AtomicBoolean accepted = new AtomicBoolean();
        return Mono.fromCallable(() -> TenantContext.callWith(tenantOf(user),
                () -> acceptUser(user, sessionId, content, clientMsgId, subject)))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(decision -> {
                accepted.set(true);
                TenantContext.runWith(tenantOf(user), () -> registry.pushToUser(user.userId(),
                    WsFrame.chatAccepted(clientMsgId, decision.userMessage())));
                return TenantContext.callWith(tenantOf(user), () -> act(user, sessionId, content, clientMsgId, decision));
            })
            .onErrorResume(e -> {
                String code = e instanceof MessageRejected rejected ? rejected.code : "CHAT-USER-DISPATCH-FAIL";
                String message = e instanceof MessageRejected ? e.getMessage() : "消息受理状态尚未确认，请先核对会话记录。";
                String state = accepted.get() ? WsFrame.ACCEPTANCE_ACCEPTED
                    : e instanceof MessageRejected ? WsFrame.ACCEPTANCE_REJECTED : WsFrame.ACCEPTANCE_UNKNOWN;
                if (!(e instanceof MessageRejected)) {
                    log.error("User message dispatch failed, errorCode={}, sessionId={}", code, sessionId, e);
                }
                TenantContext.runWith(tenantOf(user), () -> registry.pushToUser(user.userId(),
                    WsFrame.messageError(code, message, sessionId, clientMsgId, state)));
                return Mono.empty();
            })
            // 主体与租户随流下传：token 记账发生在模型调用之后、好几次线程切换之外，
            // 只有写进 Reactor Context 才能在那里还原出"这次是谁在用"
            .contextWrite(ctx -> ctx
                .put(QuotaSubjectContextThreadLocalAccessor.KEY, subject)
                .put(AgentInvocationIdentityContextThreadLocalAccessor.KEY, invocationIdentity)
                .put(TenantContextThreadLocalAccessor.KEY, tenantOf(user)));
    }

    /** 用户归属租户；令牌里没有（旧令牌）时按默认租户算，与 {@code UserJwtService} 的签发默认一致。 */
    private static String tenantOf(UserPrincipal user) {
        String tenantId = user.tenantId();
        return tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT : tenantId;
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
                Ticket ticket = ticketService.requestHandoff(sessionId, finalReason, TicketActorType.USER, user.userId());
                registry.pushToUser(user.userId(), WsFrame.system(NOTICE_HANDOFF, sessionId, ticket.getId()));
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

    /** 先查持久化回执，再在同库事务内建单和保存输入；模型与转发只在提交后执行。 */
    private Decision acceptUser(UserPrincipal user, String sessionId, String content,
                                 String clientMsgId, QuotaSubject subject) {
        if (content == null || content.isBlank()) {
            throw new MessageRejected("CHAT_MESSAGE_EMPTY", "请输入消息或添加可解析的附件。");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MESSAGE_CONTENT_MAX_BYTES) {
            throw new MessageRejected("CHAT_MESSAGE_TOO_LARGE", "消息和附件内容过长，请缩短内容后发送。");
        }
        if (clientMsgId != null && clientMsgId.length() > ChatLogService.CLIENT_MESSAGE_ID_MAX_LENGTH) {
            throw new MessageRejected("CHAT_MESSAGE_ID_INVALID", "消息标识过长，请重新输入。");
        }
        String messageId = ChatLogService.clientMessageId(tenantOf(user), sessionId,
            TicketActorType.USER, user.userId(), clientMsgId);
        SessionLock.Releasable lock = acceptanceLock.acquire("chat-accept:" + tenantOf(user) + ':' + sessionId);
        try {
            Optional<ChatMessage> existing = acceptedMessage(messageId, sessionId, TicketActorType.USER, user.userId(), content);
            if (existing.isPresent()) {
                return new Decision(Action.REPLAY_RECEIPT, null, existing.get());
            }
            SubjectQuotaDecision quota = subjectQuotaGuard.check(subject, QUOTA_RESOURCE_WS_CHAT);
            if (quota.shouldBlock()) {
                throw new MessageRejected("CHAT_QUOTA_EXCEEDED", quota.message());
            }
            Decision decision;
            try {
                decision = transactions.execute(() -> prepare(user, sessionId, content, messageId));
            } catch (RuntimeException error) {
                // 分布式锁过期或实例切换时仍由数据库唯一键裁决；回滚后才能看见竞争者提交的回执。
                existing = acceptedMessage(messageId, sessionId, TicketActorType.USER, user.userId(), content);
                if (existing.isPresent()) {
                    return new Decision(Action.REPLAY_RECEIPT, null, existing.get());
                }
                throw error;
            }
            subjectQuotaGuard.recordRequest(subject);
            return decision;
        } finally {
            lock.release();
        }
    }

    private Optional<ChatMessage> acceptedMessage(String messageId, String sessionId,
                                                  TicketActorType senderType, String senderId, String content) {
        if (messageId == null) {
            return Optional.empty();
        }
        Optional<ChatMessage> existing = chatLogService.findByMessageId(messageId);
        existing.ifPresent(message -> {
            if (!Objects.equals(message.sessionId(), sessionId) || message.senderType() != senderType
                || !Objects.equals(message.senderId(), senderId) || !Objects.equals(message.content(), content)) {
                throw new MessageRejected("CHAT_MESSAGE_ID_CONFLICT", "同一消息标识的内容不一致，请核对原消息。");
            }
        });
        return existing;
    }

    /** 明确发生在受理前的业务拒绝；数据库或网络异常只能标为未知。 */
    private static final class MessageRejected extends IllegalStateException {
        private final String code;

        private MessageRejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 阻塞准备阶段：定位/新建工单、落库用户消息、回填标题、关键词判定与状态路由。 */
    private Decision prepare(UserPrincipal user, String sessionId, String content, String messageId) {
        Ticket ticket = ticketService.findActiveBySession(sessionId)
            .orElseGet(() -> ticketService.createForSession(sessionId, user.userId(), null, TicketCategory.CONSULT));
        String ticketId = ticket.getId();
        ChatMessage userMessage = messageId == null
            ? chatLogService.append(sessionId, ticketId, TicketActorType.USER, user.userId(), content)
            : chatLogService.appendWithMessageId(messageId, sessionId, ticketId, TicketActorType.USER, user.userId(), content);
        // 首条消息前 50 字回填空标题（已有标题不覆盖）
        ticketService.fillTitle(ticketId, title(content));
        // 刷新用户最后活跃时间：用户每发一条消息即重置空闲计时基准，避免活跃会话被空闲巡检误关
        ticketService.touchUserActive(sessionId);

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
    private Mono<Void> act(UserPrincipal user, String sessionId, String content, String clientMsgId, Decision decision) {
        String userId = user.userId();
        if (decision.action() == Action.REPLAY_RECEIPT) {
            return Mono.empty();
        }
        Ticket ticket = decision.ticket();
        switch (decision.action()) {
            case AI_STREAM:
                return streamAi(user, sessionId, content, ticket.getId(), clientMsgId);
            case FORWARD_AGENT:
                registry.pushToAgent(ticket.getAssignee(), WsFrame.chat(chatData(decision.userMessage())));
                return Mono.empty();
            case WAITING_CONFIRM_NOTICE:
                registry.pushToUser(userId, WsFrame.system(NOTICE_WAITING_CONFIRM, sessionId, ticket.getId()));
                return Mono.empty();
            case HANDOFF:
                registry.pushToUser(userId, WsFrame.system(NOTICE_HANDOFF, sessionId, ticket.getId()));
                return Mono.empty();
            case WAITING_AGENT_NOTICE:
            default:
                registry.pushToUser(userId, WsFrame.system(NOTICE_WAITING_AGENT, sessionId, ticket.getId()));
                return Mono.empty();
        }
    }

    /** AI 自助流式：逐增量推 chat_chunk，完成聚合落库 BOT 消息并推 chat_done。 */
    private Mono<Void> streamAi(UserPrincipal user, String sessionId, String content, String ticketId, String clientMsgId) {
        String userId = user.userId();
        return chatTurnService.stream(sessionId, content, ticketId)
            .doOnNext(event -> TenantContext.runWith(tenantOf(user), () -> {
                if (event instanceof ChatTurnEvent.Delta delta) {
                    registry.pushToUser(userId, WsFrame.chatChunk(delta.content(), sessionId, ticketId, clientMsgId));
                } else if (event instanceof ChatTurnEvent.Completed completed) {
                    ChatMessage botMsg = completed.completion().message();
                    registry.pushToUser(userId, WsFrame.chatDone(
                        completed.completion().terminal(), botMsg, clientMsgId));
                }
            }))
            .onErrorResume(e -> {
                log.error("[session {}] ai stream dispatch failed, code={}", sessionId, "CHAT-AI-STREAM-FAIL", e);
                TenantContext.runWith(tenantOf(user), () -> registry.pushToUser(userId,
                    WsFrame.messageError("CHAT-AI-STREAM-FAIL", "消息已受理，AI 回复暂未完成，请核对会话记录。",
                        sessionId, clientMsgId, WsFrame.ACCEPTANCE_ACCEPTED)));
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
        data.put(WsFrame.KEY_ID, message.id());
        data.put(WsFrame.KEY_MESSAGE_ID, message.messageId());
        data.put(WsFrame.KEY_SESSION_ID, message.sessionId());
        data.put(WsFrame.KEY_TICKET_ID, message.ticketId());
        data.put(WsFrame.KEY_SENDER_TYPE, message.senderType().name());
        data.put(WsFrame.KEY_SENDER_ID, message.senderId());
        data.put(WsFrame.KEY_CONTENT, message.content());
        data.put(WsFrame.KEY_TS, message.createdAtMs());
        return data;
    }
}
