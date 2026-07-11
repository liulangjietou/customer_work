package com.richard.fyoung.customeradmin.workspace.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 工作区对话请求。{@code sessionId} 由前端生成并透传，标识同一次多轮对话
 * （与 agentCode 一起构成 RuntimeContext 的 (userId, sessionId)，见 AdminAgentInstanceFactory#contextFor）。
 * @author owlzhangfq@gmail.com
 */
public record ChatRequest(String sessionId, @NotBlank(message = "message 不能为空") String message) {
}
