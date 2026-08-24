package com.richard.fyoung.customeradmin.governance.change.dto;

import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernanceAuditEvent;

import java.time.LocalDateTime;

/** 可验证的审批审计事件。 */
public record GovernanceAuditEventVO(
    int sequenceNo,
    String eventType,
    Long actorId,
    String actorName,
    String detail,
    String previousHash,
    String eventHash,
    LocalDateTime retentionUntil,
    LocalDateTime createTime) {

    public static GovernanceAuditEventVO from(AiGovernanceAuditEvent event) {
        return new GovernanceAuditEventVO(event.getSequenceNo(), event.getEventType(),
            event.getActorId(), event.getActorName(), event.getDetail(), event.getPreviousHash(),
            event.getEventHash(), event.getRetentionUntil(), event.getCreateTime());
    }
}
