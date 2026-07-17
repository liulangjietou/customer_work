package com.richard.fyoung.customerwork.attachment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内附件存储（默认实现，测试 / 演示用）。
 *
 * <p>{@link ConcurrentHashMap} 保证线程安全；以 {@code @ConditionalOnMissingBean} 注册，
 * 下游声明自己的 {@link AttachmentStore} Bean 即可覆盖。重启不保留，生产建议 store-mode=jdbc。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryAttachmentStore implements AttachmentStore {

    private final ConcurrentHashMap<String, ChatAttachment> attachments = new ConcurrentHashMap<>();

    @Override
    public void save(ChatAttachment attachment) {
        if (attachment == null || attachment.getId() == null) {
            return;
        }
        attachments.put(attachment.getId(), attachment);
    }

    @Override
    public Optional<ChatAttachment> findById(String id) {
        return Optional.ofNullable(attachments.get(id));
    }

    @Override
    public List<ChatAttachment> listBySession(String sessionId) {
        return attachments.values().stream()
            .filter(a -> a.getSessionId() != null && a.getSessionId().equals(sessionId))
            .sorted(Comparator.comparing(ChatAttachment::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(Collectors.toList());
    }
}
