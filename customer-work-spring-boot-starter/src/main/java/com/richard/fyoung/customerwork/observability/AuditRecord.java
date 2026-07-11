package com.richard.fyoung.customerwork.observability;

/**
 * 审计记录（查询侧只读视图）。
 *
 * @param eventType   事件类型（tool-call / final-answer / error / approval-decision 等）
 * @param agentName   Agent 名称（形如 {@code CustomerServiceAgent-<sessionId>}，故障诊断按之回溯会话）
 * @param eventData   结构化字段 JSON 原文
 * @param createdAtMs 记录时间（毫秒时间戳）
 * @author owlzhangfq@gmail.com
 */
public record AuditRecord(String eventType, String agentName, String eventData, long createdAtMs) {
}
