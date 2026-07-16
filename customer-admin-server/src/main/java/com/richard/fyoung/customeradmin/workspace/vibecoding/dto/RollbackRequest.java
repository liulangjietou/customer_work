package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 会话一键回滚请求体。
 *
 * @param sessionId 会话 ID，用于定位 {@code sessions/{sessionId}/} 目录
 * @author owlzhangfq@gmail.com
 */
public record RollbackRequest(@NotBlank(message = "sessionId 不能为空") String sessionId) {
}
