package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Plan Mode 计划确认请求体（需求 §4.4.2）：{@code POST /plan/confirm}。
 *
 * @param sessionId 会话 ID（必须与发起 stream 时一致，用于校验会话归属并定位挂起项）
 * @param planId    待确认的计划 ID（来自 {@code plan} SSE 事件）
 * @param approved  {@code true} 批准执行 / {@code false} 拒绝该操作
 * @param note      可选备注（审计留痕，如拒绝理由）
 * @author owlzhangfq@gmail.com
 */
public record PlanConfirmRequest(@NotBlank(message = "sessionId 不能为空") String sessionId,
                                 @NotBlank(message = "planId 不能为空") String planId,
                                 @NotNull(message = "approved 不能为空") Boolean approved,
                                 String note) {
}
