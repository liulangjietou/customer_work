package com.richard.fyoung.customeradmin.openapi.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 开放 API 对话请求：在已解析出的 {@code sessionId} 上向绑定的智能体发一条 {@code message}。
 * {@code channelType + appKey} 是渠道接入层从已启用机器人配置带入的权威绑定事实，
 * admin 必须连同路径中的 {@code agentCode} 一起精确校验，不接受“某智能体存在任意渠道绑定”的宽泛放行。
 * @author owlzhangfq@gmail.com
 */
public record OpenChatRequest(
    @NotBlank(message = "sessionId 不能为空") String sessionId,
    @NotBlank(message = "message 不能为空") String message,
    @NotBlank(message = "channelType 不能为空") String channelType,
    @NotBlank(message = "appKey 不能为空") String appKey) {
}
