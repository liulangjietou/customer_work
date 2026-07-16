package com.richard.fyoung.customerwork.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息级用户反馈持久化对象（贫血数据袋）：与 {@code cw_message_feedback} 表一一映射。
 *
 * <p>领域快照见 {@link com.richard.fyoung.customerwork.feedback.MessageFeedback}（record）。
 * {@code type} 以枚举名字符串落库，转换在 Store 层完成。{@code messageId} 为自然主键（应用赋值）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_message_feedback")
public class MessageFeedbackDO {

    /** 被反馈的消息 ID（应用赋值，非自增）。 */
    @TableId(value = "message_id", type = IdType.INPUT)
    private String messageId;

    private String sessionId;
    private String type;
    private String comment;
    private Long createdAtMs;
}
