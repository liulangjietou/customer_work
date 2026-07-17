package com.richard.fyoung.customerwork.feedback;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内用户反馈存储（默认实现）。
 *
 * <p>用 {@link ConcurrentHashMap} 保证线程安全，以 messageId 为主键 upsert。
 * 以 {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link FeedbackStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryFeedbackStore implements FeedbackStore {

    private final ConcurrentHashMap<String, MessageFeedback> store = new ConcurrentHashMap<>();

    @Override
    public void save(MessageFeedback feedback) {
        if (feedback == null || feedback.messageId() == null) {
            return;
        }
        store.put(feedback.messageId(), feedback);
    }

    @Override
    public Optional<MessageFeedback> find(String messageId) {
        return Optional.ofNullable(store.get(messageId));
    }

    @Override
    public List<MessageFeedback> findBySession(String sessionId) {
        return store.values().stream()
            .filter(f -> f.sessionId().equals(sessionId))
            .sorted(Comparator.comparingLong(MessageFeedback::createdAtMs))
            .collect(Collectors.toList());
    }
}
