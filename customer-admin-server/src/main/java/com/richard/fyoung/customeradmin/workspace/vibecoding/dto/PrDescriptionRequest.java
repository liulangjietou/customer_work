package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 生成 PR description 请求体。
 *
 * @param sessionId 会话 ID，用于定位 {@code sessions/{sessionId}/} 目录
 * @author owlzhangfq@gmail.com
 */
public record PrDescriptionRequest(@NotBlank(message = "sessionId 不能为空") String sessionId) {
}
