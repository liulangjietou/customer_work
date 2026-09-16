package com.richard.fyoung.customerwork.data.chatlog;

import com.richard.fyoung.customerwork.data.ticket.TicketActorType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 聊天日志服务：落库对话消息并按会话 / 工单双维度回放历史。
 *
 * <p>{@link #append} 生成业务消息号 {@code MSG-<uuid>} 后委托 {@link ChatMessageStore} 持久化并回填
 * 自增主键；历史查询透传存储层的游标翻页能力。</p>
 * @author owlzhangfq@gmail.com
 */
public class ChatLogService {

    /** 受理入口与回执查询共用的客户端标识长度上限。 */
    public static final int CLIENT_MESSAGE_ID_MAX_LENGTH = 128;

    /** 消息表 TEXT 列的 UTF-8 字节上限，由受理入口统一检查。 */
    public static final int MESSAGE_CONTENT_MAX_BYTES = 65_535;

    private static final String MESSAGE_ID_PREFIX = "MSG-";

    private final ChatMessageStore store;

    public ChatLogService(ChatMessageStore store) {
        this.store = store;
    }

    /** 追加一条消息（生成 messageId 并落库，返回带自增主键的持久化副本）。 */
    public ChatMessage append(String sessionId, String ticketId, TicketActorType senderType,
                              String senderId, String content) {
        String messageId = MESSAGE_ID_PREFIX + UUID.randomUUID();
        return appendWithMessageId(messageId, sessionId, ticketId, senderType, senderId, content);
    }

    /** 应用受理事务已确定幂等消息号时使用；唯一键冲突交由事务外回读，不吞掉真实写入失败。 */
    public ChatMessage appendWithMessageId(String messageId, String sessionId, String ticketId,
                                           TicketActorType senderType, String senderId, String content) {
        ChatMessage message = ChatMessage.of(messageId, sessionId, ticketId, senderType, senderId, content);
        return store.append(message);
    }

    /** 标识按租户、业务范围和发送主体隔离；客户使用会话、坐席使用工单，结果适配 64 字符唯一键。 */
    public static String clientMessageId(String tenantId, String conversationScope, TicketActorType senderType,
                                          String senderId, String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return null;
        }
        StringBuilder scope = new StringBuilder();
        for (String part : List.of(tenantId, conversationScope, senderType.name(), senderId, clientMsgId)) {
            scope.append(part.length()).append(':').append(part);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.toString().getBytes(StandardCharsets.UTF_8));
            return "REQ-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /** 按业务消息号精确查询。 */
    public Optional<ChatMessage> findByMessageId(String messageId) {
        return store.findByMessageId(messageId);
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
