package com.richard.fyoung.customeradmin.businessoutcome.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 客服端真实表聚合出的业务结果原始行。 */
@Data
public class BusinessOutcomeAggregateRow {

    private Long totalSessions;
    private Long successfulSessions;
    private Long autoResolvedProxySessions;
    private Long handoffSessions;
    private Long totalCalls;
    private Long knownTokenCalls;
    private Long unknownTokenCalls;
    private Long knownTotalTokens;
    private Long csatInvitedSessions;
    private Long csatRespondedSessions;
    private Long csatSatisfiedSessions;
    private BigDecimal averageCsat;
    private Long modelSegmentCount;
    private Long settledCostSegmentCount;
    private Long unsettledCostSegmentCount;
    private Long multiCurrencyCalls;
    private Long costCurrencyCount;
    private String costCurrency;
    private BigDecimal settledCostAmount;
}
