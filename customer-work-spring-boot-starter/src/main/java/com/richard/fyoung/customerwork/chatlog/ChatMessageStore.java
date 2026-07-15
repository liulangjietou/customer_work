package com.richard.fyoung.customerwork.chatlog;

import java.util.List;

/**
 * 聊天消息存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryChatMessageStore}；生产按 {@code customer-work.chat-log.store-mode=jdbc}
 * 切换为 {@link JdbcChatMessageStore}。历史查询支持基于自增主键的游标翻页（{@code beforeId}）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ChatMessageStore {

    /** 追加一条消息，返回回填了自增主键的副本。 */
    ChatMessage append(ChatMessage message);

    /**
     * 查会话历史（游标翻页）。
     *
     * @param sessionId 会话
     * @param beforeId  游标：只取 id 小于该值的更早消息；{@code null} 表示取最新一页
     * @param limit     本页最大条数
     * @return 按 id 升序排列的该页消息
     */
    List<ChatMessage> findBySession(String sessionId, Long beforeId, int limit);

    /** 查工单历史（游标翻页，语义同 {@link #findBySession}）。 */
    List<ChatMessage> findByTicket(String ticketId, Long beforeId, int limit);
}
