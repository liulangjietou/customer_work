package com.richard.fyoung.customerwork.dto;

import com.richard.fyoung.customerwork.feedback.FeedbackType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 消息级反馈提交请求体。
 *
 * @param sessionId 所属会话 ID。
 * @param messageId 被反馈的消息 ID（来自 {@link ChatResponse#messageId()}）。
 * @param type      反馈类型：UP / DOWN。
 * @param comment   可选的文字说明。
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "消息级反馈提交请求体")
public record FeedbackRequest(
        @Schema(description = "所属会话 ID", example = "u1001:conv-1")
        @NotBlank(message = "sessionId 不能为空")
        String sessionId,

        @Schema(description = "被反馈的消息 ID（来自 /chat 响应的 messageId）", example = "MSG-4bf92f35-77b3-4da6-a3ce")
        @NotBlank(message = "messageId 不能为空")
        String messageId,

        @Schema(description = "反馈类型：UP（点赞）/ DOWN（点踩）", example = "DOWN")
        @NotNull(message = "type 不能为空")
        FeedbackType type,

        @Schema(description = "可选文字说明", example = "回复没有解决我的退款问题")
        @Size(max = 500, message = "comment 长度不能超过 500")
        String comment) {
}
