package com.richard.fyoung.customerwork.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 客服对话请求体。
 *
 * @param sessionId 会话 ID。生产中由接入层（网关 / 前端）生成并透传，用于会话恢复与多轮上下文；
 *                  为空时服务端按匿名会话处理。
 * @param message   用户输入的文本内容，必填。
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "客服对话请求体")
public record ChatRequest(
        @Schema(description = "会话 ID（可写成 租户ID:会话ID 以共享长期记忆）；为空按匿名处理",
                example = "u1001:conv-1")
        @Size(max = 128, message = "sessionId 长度不能超过 128")
        String sessionId,

        @Schema(description = "用户输入文本", example = "帮我查一下订单 20260613001 的状态和物流",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 长度不能超过 4000")
        String message) {
}
