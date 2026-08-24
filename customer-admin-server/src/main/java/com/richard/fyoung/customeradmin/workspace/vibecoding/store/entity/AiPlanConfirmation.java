package com.richard.fyoung.customeradmin.workspace.vibecoding.store.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** ai_plan_confirmation 挂起态读模型。 */
@Data
public class AiPlanConfirmation {
    private String tenantId;
    private String agentCode;
    private String sessionId;
    private String planId;
    private String status;
    private LocalDateTime expireAt;
}
