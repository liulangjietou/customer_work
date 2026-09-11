package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.infra.lock.SessionLock;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 坐席 HTTP 与 WS 共用的回复受理编排：校验工单、原子保存、提交后推送；重试只返回已有记录。 */
@Service
public class AgentMessageAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(AgentMessageAcceptanceService.class);
    private final TicketService tickets;
    private final ChatLogService chatLog;
    private final WsSessionRegistry registry;
    private final SessionLock locks;
    private final CustomerWorkTransactionExecutor transactions;

    @Autowired
    public AgentMessageAcceptanceService(TicketService tickets, ChatLogService chatLog,
                                          WsSessionRegistry registry, SessionLock locks,
                                          ObjectProvider<CustomerWorkTransactionExecutor> transactions) {
        this(tickets, chatLog, registry, locks,
            transactions.getIfAvailable(() -> CustomerWorkTransactionExecutor.DIRECT));
    }

    /** 显式事务入口供嵌入式调用和同库事务测试使用。 */
    public AgentMessageAcceptanceService(TicketService tickets, ChatLogService chatLog,
                                          WsSessionRegistry registry, SessionLock locks,
                                          CustomerWorkTransactionExecutor transactions) {
        this.tickets = tickets;
        this.chatLog = chatLog;
        this.registry = registry;
        this.locks = locks;
        this.transactions = transactions;
    }

    public enum Rejection {
        INVALID_INPUT, NOT_ASSIGNEE, NOT_REPLYABLE, ID_CONFLICT
    }

    /** 仅表示确定拒绝的业务条件；基础设施失败不得被误译为“未受理”。 */
    public static class Rejected extends RuntimeException {
        private final Rejection reason;

        /** 保留明确拒绝原因，供不同接入协议映射相同的业务语义。 */
        public Rejected(Rejection reason, String message) {
            super(message);
            this.reason = reason;
        }
        public Rejection reason() {
            return reason;
        }

        public String code() {
            return "AGENT_REPLY_" + reason.name();
        }
    }

    /** message 为空表示已完成查询但没有找到记录，不代表查询失败。 */
    public record Receipt(String clientMsgId, ChatMessage message) { }

    private record Accepted(ChatMessage message, String userId, boolean replay) { }

    /** 调用方先绑定已鉴权的租户与坐席；返回值只取实际消息存储，不把网络写出当作用户收到。 */
    public ChatMessage accept(String agentId, String ticketId, String content, String clientMsgId) {
        validate(content, clientMsgId);
        String tenant = TenantContext.require();
        String messageId = messageId(tenant, ticketId, agentId, clientMsgId);
        SessionLock.Releasable lock = locks.acquire("agent-reply:" + tenant + ':' + ticketId);
        Accepted accepted;
        try {
            accepted = transactions.execute(() -> {
                Ticket ticket = requireTicket(ticketId, true);
                Optional<ChatMessage> saved = existing(ticket, agentId, messageId);
                if (saved.isPresent()) {
                    checkContent(saved.get(), content);
                    return new Accepted(saved.get(), ticket.getUserId(), true);
                }
                try {
                    ticket.requireAgentReply(agentId);
                } catch (SecurityException error) {
                    throw new Rejected(Rejection.NOT_ASSIGNEE, "工单已由其他坐席处理，请刷新工单。");
                } catch (IllegalStateException error) {
                    throw new Rejected(Rejection.NOT_REPLYABLE, "当前工单状态不支持回复，请刷新工单。");
                }
                ChatMessage message = messageId == null
                    ? chatLog.append(ticket.getSessionId(), ticketId, TicketActorType.AGENT, agentId, content)
                    : chatLog.appendWithMessageId(messageId, ticket.getSessionId(), ticketId,
                        TicketActorType.AGENT, agentId, content);
                return new Accepted(message, ticket.getUserId(), false);
            });
        } catch (Rejected | NoSuchElementException error) {
            throw error;
        } catch (RuntimeException error) {
            // 在失败事务之外回读唯一键胜者；查不到或查询失败均保留“结果未知”的错误语义。
            Receipt receipt = receipt(agentId, ticketId, clientMsgId);
            if (receipt.message() == null) {
                throw new DataAccessResourceFailureException("agent reply acceptance failed", error);
            }
            checkContent(receipt.message(), content);
            accepted = new Accepted(receipt.message(), null, true);
        } finally {
            lock.release();
        }
        if (!accepted.replay()) {
            try {
                registry.pushToUser(accepted.userId(), WsFrame.chatMessage(accepted.message()));
            } catch (RuntimeException error) {
                // 已提交的回复仍返回真实回执，离线恢复从同一消息表读取。
                log.error("agent reply notification failed, errorCode={}, messageId={}",
                    "AGENT_REPLY_NOTIFY_FAILED", accepted.message().messageId(), error);
            }
        }
        return accepted.message();
    }

    /** 已转派或关闭后仍可核对本人发出的回复；查不到当前租户工单时不暴露消息。 */
    public Receipt receipt(String agentId, String ticketId, String clientMsgId) {
        validateId(clientMsgId);
        Ticket ticket = requireTicket(ticketId, false);
        return new Receipt(clientMsgId,
            existing(ticket, agentId, messageId(TenantContext.require(), ticketId, agentId, clientMsgId))
                .orElse(null));
    }

    private Ticket requireTicket(String ticketId, boolean locked) {
        try {
            return (locked ? tickets.findForUpdate(ticketId) : tickets.find(ticketId))
                .orElseThrow(() -> new NoSuchElementException("ticket not found"));
        } catch (IllegalStateException error) {
            throw new DataAccessResourceFailureException("agent reply ticket lookup failed", error);
        }
    }

    private Optional<ChatMessage> existing(Ticket ticket, String agentId, String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        return chatLog.findByMessageId(messageId).filter(message ->
            ticket.getId().equals(message.ticketId()) && ticket.getSessionId().equals(message.sessionId())
                && message.senderType() == TicketActorType.AGENT && agentId.equals(message.senderId()));
    }

    private static String messageId(String tenant, String ticketId, String agentId, String clientMsgId) {
        return ChatLogService.clientMessageId(tenant, ticketId, TicketActorType.AGENT, agentId, clientMsgId);
    }

    private static void checkContent(ChatMessage message, String content) {
        if (!Objects.equals(message.content(), content)) {
            throw new Rejected(Rejection.ID_CONFLICT, "此发送标识已用于另一条回复，请核对历史消息。");
        }
    }

    private static void validate(String content, String clientMsgId) {
        if (!StringUtils.hasText(content)
            || content.getBytes(StandardCharsets.UTF_8).length > ChatLogService.MESSAGE_CONTENT_MAX_BYTES) {
            throw new Rejected(Rejection.INVALID_INPUT, "请输入回复内容，并将长度控制在存储上限内。");
        }
        validateId(clientMsgId);
    }

    private static void validateId(String clientMsgId) {
        if (clientMsgId != null && clientMsgId.length() > ChatLogService.CLIENT_MESSAGE_ID_MAX_LENGTH) {
            throw new Rejected(Rejection.INVALID_INPUT, "消息标识过长，请重新输入。");
        }
    }

}
