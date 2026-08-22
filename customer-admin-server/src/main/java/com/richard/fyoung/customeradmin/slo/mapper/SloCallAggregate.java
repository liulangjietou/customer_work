package com.richard.fyoung.customeradmin.slo.mapper;

import lombok.Data;

/** 调用日志聚合结果，所有计数均来自真实 success/duration_ms 字段。 */
@Data
public class SloCallAggregate {
    private Long total;
    private Long availabilityGood;
    private Long latencyGood;
    private Long compositeGood;
}
