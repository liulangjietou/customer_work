package com.example.customerwork.dto;

/**
 * 客服对话响应体（非流式）。
 *
 * @param sessionId 实际使用的会话 ID（匿名请求会回填服务端生成的 ID）。
 * @param reply     助手回复文本。
 */
public record ChatResponse(String sessionId, String reply) {
}
