package com.example.customerwork.dto;

/**
 * 客服对话请求体。
 *
 * @param sessionId 会话 ID。生产中由接入层（网关/前端）生成并透传，用于会话恢复与多轮上下文。
 * @param message   用户输入的文本内容。
 */
public record ChatRequest(String sessionId, String message) {
}
