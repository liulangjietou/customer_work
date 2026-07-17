package com.richard.fyoung.customerwork.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 客服对话响应体（非流式）。
 *
 * @param sessionId 实际使用的会话 ID（匿名请求会回填服务端生成的 ID）。
 * @param reply     助手回复文本。
 * @param messageId 本条回复的消息 ID，供 {@code POST /api/customer/feedback} 提交点赞/点踩时引用。
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "客服对话响应体")
public record ChatResponse(
        @Schema(description = "实际使用的会话 ID（匿名请求会回填服务端生成的 ID）", example = "u1001:conv-1")
        String sessionId,
        @Schema(description = "助手回复文本", example = "订单 20260613001：状态=已发货，金额=299.00 元。")
        String reply,
        @Schema(description = "消息 ID（用于提交反馈）", example = "MSG-4bf92f35-77b3-4da6-a3ce")
        String messageId) {
}
