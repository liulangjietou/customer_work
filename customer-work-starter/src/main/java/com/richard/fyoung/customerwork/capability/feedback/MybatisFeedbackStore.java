package com.richard.fyoung.customerwork.capability.feedback;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.capability.feedback.entity.MessageFeedbackDO;
import com.richard.fyoung.customerwork.capability.feedback.mapper.FeedbackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 用户反馈存储（生产实现：{@code feedback.store-mode=jdbc} 时装配）。
 *
 * <p>把消息级反馈结构化写入 {@code cw_message_feedback} 表，保证应用重启 / 多实例部署下反馈不丢失
 * （进程内 {@link InMemoryFeedbackStore} 重启即清空）。以 {@code messageId} 为主键 upsert，
 * 同一消息重复反馈以最新一次为准。</p>
 *
 * <p>{@link #save} 沿用旧实现语义：失败只记 error、不抛异常（反馈非核心链路，不应阻断主流程）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisFeedbackStore implements FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisFeedbackStore.class);

    private final FeedbackMapper mapper;

    public MybatisFeedbackStore(FeedbackMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(MessageFeedback feedback) {
        if (feedback == null || feedback.messageId() == null) {
            return;
        }
        try {
            mapper.upsert(toDO(feedback));
        } catch (Exception e) {
            log.error("[MybatisFeedbackStore] save failed, errorCode={}, messageId={}",
                "FEEDBACK-STORE-SAVE-FAIL", feedback.messageId(), e);
        }
    }

    @Override
    public Optional<MessageFeedback> find(String messageId) {
        try {
            MessageFeedbackDO row = mapper.selectById(messageId);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisFeedbackStore] find failed, errorCode={}, messageId={}",
                "FEEDBACK-STORE-FIND-FAIL", messageId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<MessageFeedback> findBySession(String sessionId) {
        try {
            QueryWrapper<MessageFeedbackDO> wrapper = new QueryWrapper<MessageFeedbackDO>()
                .eq("session_id", sessionId)
                .orderByAsc("created_at_ms");
            List<MessageFeedbackDO> rows = mapper.selectList(wrapper);
            List<MessageFeedback> result = new ArrayList<>(rows.size());
            for (MessageFeedbackDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisFeedbackStore] findBySession failed, errorCode={}, sessionId={}",
                "FEEDBACK-STORE-FINDBYSESSION-FAIL", sessionId, e);
            return List.of();
        }
    }

    private MessageFeedback toDomain(MessageFeedbackDO row) {
        return new MessageFeedback(
            row.getMessageId(),
            row.getSessionId(),
            FeedbackType.valueOf(row.getType()),
            row.getComment(),
            row.getCreatedAtMs());
    }

    private MessageFeedbackDO toDO(MessageFeedback feedback) {
        MessageFeedbackDO row = new MessageFeedbackDO();
        row.setMessageId(feedback.messageId());
        row.setSessionId(feedback.sessionId());
        row.setType(feedback.type().name());
        row.setComment(feedback.comment());
        row.setCreatedAtMs(feedback.createdAtMs());
        return row;
    }
}
