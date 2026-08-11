package com.richard.fyoung.customerwork.capability.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.feedback.entity.MessageFeedbackDO;

/**
 * 消息级用户反馈 Mapper：继承 {@link BaseMapper} 复用单表 CRUD；
 * {@link #upsert} 表达按 {@code message_id} 的 {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 * @author owlzhangfq@gmail.com
 */
public interface FeedbackMapper extends BaseMapper<MessageFeedbackDO> {

    /** 按主键 upsert：同一消息重复反馈以最新一次覆盖。 */
    int upsert(MessageFeedbackDO record);
}
