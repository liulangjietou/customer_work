package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * AI 代码审查请求（需求文档 §4.2.2）：对指定会话本轮相对 baseline 的 diff 做结构化审查。
 *
 * @param sessionId 会话 ID（与本轮 {@code stream} 调用同一个）
 * @author owlzhangfq@gmail.com
 */
public record ReviewRequest(@NotBlank String sessionId) {
}
