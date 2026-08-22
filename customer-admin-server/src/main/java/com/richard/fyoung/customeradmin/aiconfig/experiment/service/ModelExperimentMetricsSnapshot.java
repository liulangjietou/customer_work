package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentMetricsAvailability;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 指标数据面向控制面的稳定内部契约；后续 call-log 聚合实现只需替换 Provider。 */
public record ModelExperimentMetricsSnapshot(
    ModelExperimentMetricsAvailability availability,
    String message,
    Long samples,
    BigDecimal errorRate,
    Long p95LatencyMs,
    Arm control,
    Arm treatment,
    LocalDateTime evaluatedAt
) {

    public static ModelExperimentMetricsSnapshot awaitingRuntime() {
        return new ModelExperimentMetricsSnapshot(
            ModelExperimentMetricsAvailability.AWAITING_RUNTIME,
            "运行时尚未写入 experimentId/revision/arm，当前无可归属的真实实验指标",
            null, null, null, null, null, null);
    }

    public boolean isReady() {
        return ModelExperimentMetricsAvailability.READY == availability;
    }

    /** 单臂真实聚合值。 */
    public record Arm(Long samples, BigDecimal errorRate, Long p95LatencyMs) {
    }
}
