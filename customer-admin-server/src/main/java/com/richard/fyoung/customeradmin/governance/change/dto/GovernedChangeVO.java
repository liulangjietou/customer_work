package com.richard.fyoung.customeradmin.governance.change.dto;

import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;

import java.time.LocalDateTime;

/** 高风险变更视图；payload 只在服务端执行，不返回浏览器。 */
public record GovernedChangeVO(
    String id,
    String changeType,
    String targetKey,
    String payloadHash,
    Long makerId,
    String makerName,
    Long checkerId,
    String checkerName,
    String status,
    String decisionReason,
    String resultJson,
    String failureCode,
    LocalDateTime expiresAt,
    LocalDateTime decidedAt,
    LocalDateTime executedAt,
    LocalDateTime createTime,
    LocalDateTime updateTime) {

    public static GovernedChangeVO from(AiGovernedChangeRequest entity) {
        return new GovernedChangeVO(entity.getId(), entity.getChangeType(), entity.getTargetKey(),
            entity.getPayloadHash(), entity.getMakerId(), entity.getMakerName(),
            entity.getCheckerId(), entity.getCheckerName(), entity.getStatus(),
            entity.getDecisionReason(), entity.getResultJson(), entity.getFailureCode(),
            entity.getExpiresAt(), entity.getDecidedAt(), entity.getExecutedAt(),
            entity.getCreateTime(), entity.getUpdateTime());
    }
}
