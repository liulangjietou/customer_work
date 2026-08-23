package com.richard.fyoung.customeradmin.aiconfig.experiment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 实验指标只读投影；数据源不可用时数值保持为空，绝不生成样例数据。 */
public record ModelExperimentMetricsVO(
    Long experimentId,
    String availability,
    String message,
    Long samples,
    BigDecimal errorRate,
    Long p95LatencyMs,
    ModelExperimentArmMetricsVO control,
    ModelExperimentArmMetricsVO treatment,
    LocalDateTime evaluatedAt
) {
}
