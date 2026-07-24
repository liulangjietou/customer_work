package com.richard.fyoung.customeradmin.openapi.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 开放 API 对话请求：在已解析出的 {@code sessionId} 上向绑定的智能体发一条 {@code message}。
 * @author owlzhangfq@gmail.com
 */
public record OpenChatRequest(
    @NotBlank(message = "sessionId 不能为空") String sessionId,
    @NotBlank(message = "message 不能为空") String message) {
}
