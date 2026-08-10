package com.richard.fyoung.customerwork.capability.feedback;

/**
 * 消息级反馈类型（用户对话点赞/点踩）。
 * @author owlzhangfq@gmail.com
 */
public enum FeedbackType {

    /** 点赞：回复有帮助。 */
    UP,

    /** 点踩：回复不满意，负反馈信号，驱动数据飞轮。 */
    DOWN
}
