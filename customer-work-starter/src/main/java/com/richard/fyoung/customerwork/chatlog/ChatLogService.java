package com.richard.fyoung.customerwork.chatlog;

import com.richard.fyoung.customerwork.ticket.TicketActorType;

import java.util.List;
import java.util.UUID;

/**
 * 聊天日志服务：落库对话消息并按会话 / 工单双维度回放历史。
 *
 * <p>{@link #append} 生成业务消息号 {@code MSG-<uuid>} 后委托 {@link ChatMessageStore} 持久化并回填
 * 自增主键；历史查询透传存储层的游标翻页能力。</p>
 * @author owlzhangfq@gmail.com
 */
public class ChatLogService {

    private static final String MESSAGE_ID_PREFIX = "MSG-";

    private final ChatMessageStore store;

    public ChatLogService(ChatMessageStore store) {
        this.store = store;
    }

    /** 追加一条消息（生成 messageId 并落库，返回带自增主键的持久化副本）。 */
    public ChatMessage append(String sessionId, String ticketId, TicketActorType senderType,
                              String senderId, String content) {
        String messageId = MESSAGE_ID_PREFIX + UUID.randomUUID();
        ChatMessage message = ChatMessage.of(messageId, sessionId, ticketId, senderType, senderId, content);
        return store.append(message);
    }

    /** 会话历史（游标翻页，按 id 升序）。 */
    public List<ChatMessage> historyBySession(String sessionId, Long beforeId, int limit) {
        return store.findBySession(sessionId, beforeId, limit);
    }

    /** 工单历史（游标翻页，按 id 升序）。 */
    public List<ChatMessage> historyByTicket(String ticketId, Long beforeId, int limit) {
        return store.findByTicket(ticketId, beforeId, limit);
    }
}
