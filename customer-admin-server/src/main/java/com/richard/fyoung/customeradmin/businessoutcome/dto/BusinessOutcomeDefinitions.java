package com.richard.fyoung.customeradmin.businessoutcome.dto;

/** 返回给运营人员的可审计指标口径。 */
public record BusinessOutcomeDefinitions(
    String observedSession,
    String successfulSession,
    String autoResolvedProxy,
    String handoffSession,
    String csat,
    String token,
    String cost
) {
}
