package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import java.util.List;

/**
 * {@code plan} SSE 事件载荷（需求 §4.4.2 / §6.2）：Plan Mode 命中高风险操作时下发，流随即挂起
 * 等待用户对该 {@code planId} 的确认。
 *
 * @param planId               本次确认的唯一标识（与 {@code POST /plan/confirm} 的 planId 对应）
 * @param actions              本步待确认的高风险操作清单
 * @param reason               总体风险说明（各 action reason 的聚合，便于卡片标题展示）
 * @param requiresConfirmation 固定 {@code true}——出现 plan 事件即意味着需要确认
 * @param timeoutSeconds       确认超时秒数（前端据此显示倒计时），超时后服务端自动按拒绝处理
 * @author owlzhangfq@gmail.com
 */
public record PlanEvent(String planId, List<PlanAction> actions, String reason,
                        boolean requiresConfirmation, int timeoutSeconds) {
}
