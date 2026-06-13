package com.example.customerwork.dto;

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
public record ChatRequest(
        @Size(max = 128, message = "sessionId 长度不能超过 128")
        String sessionId,

        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 长度不能超过 4000")
        String message) {
}
