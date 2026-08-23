package com.richard.fyoung.customeradmin.businessoutcome.dto;

import java.math.BigDecimal;

/** 当前租户在指定真实调用窗口内的业务结果与成本语义视图。 */
public record BusinessOutcomeSummaryVO(
    String tenantId,
    String agentCode,
    long fromMs,
    long toMs,
    long generatedAtMs,
    String dataSource,
    long totalSessions,
    long successfulSessions,
    BigDecimal successfulSessionRate,
    long autoResolvedProxySessions,
    BigDecimal autoResolvedProxyRate,
    long handoffSessions,
    BigDecimal handoffRate,
    long totalCalls,
    Long totalTokens,
    MetricAvailability tokenAvailability,
    long csatInvitedSessions,
    long csatRespondedSessions,
    BigDecimal csatResponseRate,
    BigDecimal averageCsat,
    BigDecimal csatSatisfiedRate,
    BigDecimal totalCost,
    BigDecimal costPerAutoResolvedSession,
    MetricAvailability costAvailability,
    BusinessOutcomeDefinitions definitions
) {
}
