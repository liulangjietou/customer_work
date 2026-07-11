package com.richard.fyoung.customeradmin.workspace.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 把一个会话加入项目的请求。
 * @author owlzhangfq@gmail.com
 */
public record AddSessionRequest(
    @NotBlank(message = "agentCode 不能为空") String agentCode,
    @NotBlank(message = "sessionId 不能为空") String sessionId) {
}
