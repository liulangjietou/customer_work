package com.richard.fyoung.customeradmin.aiconfig.experiment.dto;

import java.math.BigDecimal;

/** 单个实验臂的只读指标。 */
public record ModelExperimentArmMetricsVO(
    Long samples,
    BigDecimal errorRate,
    Long p95LatencyMs
) {
}
