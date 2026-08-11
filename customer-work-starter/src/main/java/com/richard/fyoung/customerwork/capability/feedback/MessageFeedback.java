package com.richard.fyoung.customerwork.capability.feedback;

/**
 * 消息级用户反馈（不可变事实快照，非状态机实体）。
 *
 * <p>与 {@link com.richard.fyoung.customerwork.capability.approval.ApprovalRequest}/
 * {@link com.richard.fyoung.customerwork.capability.handoff.HandoffTicket} 等有状态流转的充血模型不同——
 * 反馈是"用户对某条消息的一次性态度表达"，没有合理的状态机（不存在"待确认的反馈"）。
 * 以 {@code messageId} 为自然主键，重复提交按最新一次覆盖（upsert，用户改变主意允许更正）。</p>
 *
 * @param messageId  被反馈的消息 ID（来自 {@code ChatResponse#messageId}）
 * @param sessionId  所属会话 ID
 * @param type       反馈类型：UP / DOWN
 * @param comment    可选的文字说明
 * @param createdAtMs 提交时间戳（毫秒；重复提交时为最新一次的时间）
 * @author owlzhangfq@gmail.com
 */
public record MessageFeedback(
    String messageId,
    String sessionId,
    FeedbackType type,
    String comment,
    long createdAtMs
) {
}
