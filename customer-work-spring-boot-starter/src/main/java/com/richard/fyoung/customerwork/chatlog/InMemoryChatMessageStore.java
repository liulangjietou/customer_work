package com.richard.fyoung.customerwork.chatlog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 进程内聊天消息存储（默认实现，测试 / 演示用）。
 *
 * <p>{@link ConcurrentLinkedQueue} 承载全部消息，{@link AtomicLong} 模拟自增主键。游标翻页语义与
 * {@link JdbcChatMessageStore} 一致：{@code beforeId} 为 null 取最新一页，否则取更早消息，均按 id 升序返回。
 * 以 {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link ChatMessageStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryChatMessageStore implements ChatMessageStore {

    private final ConcurrentLinkedQueue<ChatMessage> messages = new ConcurrentLinkedQueue<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public ChatMessage append(ChatMessage message) {
        ChatMessage persisted = message.withId(idSeq.incrementAndGet());
        messages.add(persisted);
        return persisted;
    }

    @Override
    public List<ChatMessage> findBySession(String sessionId, Long beforeId, int limit) {
        return page(m -> sessionId != null && sessionId.equals(m.sessionId()), beforeId, limit);
    }

    @Override
    public List<ChatMessage> findByTicket(String ticketId, Long beforeId, int limit) {
        return page(m -> ticketId != null && ticketId.equals(m.ticketId()), beforeId, limit);
    }

    /** 游标翻页：过滤维度 → 应用 beforeId 游标 → 取最新 limit 条 → 升序返回。 */
    private List<ChatMessage> page(Predicate<ChatMessage> dimension, Long beforeId, int limit) {
        List<ChatMessage> desc = messages.stream()
            .filter(dimension)
            .filter(m -> beforeId == null || m.id() < beforeId)
            .sorted(Comparator.comparingLong(ChatMessage::id).reversed())
            .limit(Math.max(limit, 0))
            .collect(Collectors.toList());
        List<ChatMessage> asc = new ArrayList<>(desc);
        asc.sort(Comparator.comparingLong(ChatMessage::id));
        return asc;
    }
}
