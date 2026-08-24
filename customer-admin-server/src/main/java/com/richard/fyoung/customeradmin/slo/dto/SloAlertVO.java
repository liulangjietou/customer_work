package com.richard.fyoung.customeradmin.slo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLO 告警周期展示对象。 */
public record SloAlertVO(
    Long id,
    Long policyId,
    String policyName,
    String scopeType,
    String scopeKey,
    String status,
    BigDecimal shortBurnRate,
    BigDecimal longBurnRate,
    LocalDateTime firstSeenAt,
    LocalDateTime lastSeenAt,
    Long ackBy,
    LocalDateTime ackAt,
    LocalDateTime resolvedAt
) {
}
