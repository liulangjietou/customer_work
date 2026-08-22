package com.richard.fyoung.customeradmin.slo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLO 策略展示对象。 */
public record SloPolicyVO(
    Long id,
    String policyName,
    String scopeType,
    String scopeKey,
    BigDecimal availabilityTarget,
    BigDecimal latencyTarget,
    Long latencyThresholdMs,
    Integer shortWindowMinutes,
    Integer longWindowMinutes,
    Integer minimumSampleCount,
    BigDecimal burnRateThreshold,
    Boolean enabled,
    LocalDateTime updateTime
) {
}
