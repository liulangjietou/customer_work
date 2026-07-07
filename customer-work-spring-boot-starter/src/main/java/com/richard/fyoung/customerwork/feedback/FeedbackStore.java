package com.richard.fyoung.customerwork.feedback;

import java.util.List;
import java.util.Optional;

/**
 * 用户反馈存储 SPI（持久化扩展点）。
 *
 * <p>默认由 {@link InMemoryFeedbackStore} 提供进程内实现（离线可测、确定性）；
 * 生产可替换为 JDBC / Redis 等持久化实现（声明同类型 Bean 覆盖默认），保证反馈重启不丢失。</p>
 *
 * <p>语义约定：{@link #save} 是按 {@code messageId} 的 upsert（同一消息重复反馈以最新一次为准）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface FeedbackStore {

    /** 保存（新建或覆盖）一条反馈；以 messageId 为主键 upsert。 */
    void save(MessageFeedback feedback);

    /** 按消息 ID 查找反馈。 */
    Optional<MessageFeedback> find(String messageId);

    /** 按会话查找全部反馈（时间正序）。 */
    List<MessageFeedback> findBySession(String sessionId);
}
