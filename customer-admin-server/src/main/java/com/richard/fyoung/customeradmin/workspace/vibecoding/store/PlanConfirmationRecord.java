package com.richard.fyoung.customeradmin.workspace.vibecoding.store;

import java.time.LocalDateTime;

/** 跨 Pod 共享的 Plan/HITL 挂起快照。 */
public record PlanConfirmationRecord(
    String tenantId,
    String agentCode,
    String sessionId,
    String planId,
    PlanConfirmationState state,
    LocalDateTime expireAt
) {
}
