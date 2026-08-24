package com.richard.fyoung.customerwork.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 客服对话响应体（非流式）。
 *
 * @param sessionId 实际使用的会话 ID（匿名请求会回填服务端生成的 ID）。
 * @param reply     助手回复文本。
 * @param messageId    已持久化消息的 ID，供 {@code POST /api/customer/feedback} 提交点赞/点踩时引用。
 * @param finishReason Agent 终止原因。
 * @param usage        本轮真实 token 用量。
 * @param traceId      本轮调用链标识。
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "客服对话响应体")
public record ChatResponse(
        @Schema(description = "实际使用的会话 ID（匿名请求会回填服务端生成的 ID）", example = "u1001:conv-1")
        String sessionId,
        @Schema(description = "助手回复文本", example = "订单 20260613001：状态=已发货，金额=299.00 元。")
        String reply,
        @Schema(description = "消息 ID（用于提交反馈）", example = "MSG-4bf92f35-77b3-4da6-a3ce")
        String messageId,
        @Schema(description = "Agent 终止原因", example = "MODEL_STOP")
        String finishReason,
        @Schema(description = "本轮 token 用量")
        ChatUsageSnapshot usage,
        @Schema(description = "调用链标识")
        String traceId) {

    public static ChatResponse from(String sessionId, String reply, ChatTerminalEnvelope terminal) {
        return new ChatResponse(sessionId, reply, terminal.messageId(), terminal.finishReason(),
            terminal.usage(), terminal.traceId());
    }
}
