package com.richard.fyoung.customeradmin.slo.dto;

import java.time.LocalDateTime;

/** 同步评估结果。短、长窗均达到最低样本且同时超过阈值时才进入 BURNING。 */
public record SloEvaluationVO(
    Long policyId,
    String policyName,
    String scopeType,
    String scopeKey,
    LocalDateTime evaluatedAt,
    String status,
    int minimumSampleCount,
    SloWindowEvaluation shortWindow,
    SloWindowEvaluation longWindow,
    boolean alertCreated
) {
}
