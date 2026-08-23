package com.richard.fyoung.customeradmin.slo.dto;

import java.math.BigDecimal;

/** 单个观测窗口的真实调用统计与预算消耗。 */
public record SloWindowEvaluation(
    int windowMinutes,
    long total,
    long good,
    long bad,
    long availabilityGood,
    long latencyGood,
    BigDecimal availabilityRatio,
    BigDecimal latencyRatio,
    BigDecimal remainingErrorBudget,
    BigDecimal burnRate
) {
}
