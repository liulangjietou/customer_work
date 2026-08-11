package com.richard.fyoung.customerwork.data.chatlog;

import com.richard.fyoung.customerwork.data.ticket.TicketActorType;

/**
 * 聊天消息（不可变）：会话与工单双维度的对话留痕。
 *
 * <p>{@code id} 为存储层自增主键（游标翻页用），追加前置 0 由存储层回填；{@code messageId} 为业务
 * 幂等标识 {@code MSG-<uuid>}。{@code senderType} 复用 {@link TicketActorType}（USER/BOT/AGENT/SYSTEM）
 * 标识发送方；{@code ticketId} 可空（未转人工的纯 AI 对话消息不关联工单）。</p>
 *
 * @param id          存储层自增主键（追加前为 0）
 * @param messageId   业务消息号 MSG-&lt;uuid&gt;
 * @param sessionId   所属会话
 * @param ticketId    关联工单号（可空）
 * @param senderType  发送方类型
 * @param senderId    发送方标识（可空）
 * @param content     消息内容
 * @param createdAtMs 创建时间戳（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record ChatMessage(long id, String messageId, String sessionId, String ticketId,
                          TicketActorType senderType, String senderId, String content, long createdAtMs) {

    /** 新建消息工厂：id 交由存储层回填（此处置 0），时间戳取当前。 */
    public static ChatMessage of(String messageId, String sessionId, String ticketId,
                                 TicketActorType senderType, String senderId, String content) {
        return new ChatMessage(0L, messageId, sessionId, ticketId, senderType, senderId, content,
            System.currentTimeMillis());
    }

    /** 回填自增主键后的副本。 */
    public ChatMessage withId(long assignedId) {
        return new ChatMessage(assignedId, messageId, sessionId, ticketId, senderType, senderId,
            content, createdAtMs);
    }
}
