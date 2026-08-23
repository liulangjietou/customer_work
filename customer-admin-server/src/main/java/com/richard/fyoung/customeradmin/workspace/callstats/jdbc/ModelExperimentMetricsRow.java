package com.richard.fyoung.customeradmin.workspace.callstats.jdbc;

import lombok.Data;

/** 调用日志按实验臂聚合的真实样本、错误数与 nearest-rank P95。 */
@Data
public class ModelExperimentMetricsRow {
    private String arm;
    private Long samples;
    private Long errors;
    private Long p95LatencyMs;
}
