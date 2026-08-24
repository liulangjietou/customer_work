package com.richard.fyoung.customeradmin.slo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLO 告警状态迁移事件展示对象。 */
public record SloAlertEventVO(
    Long id,
    String eventType,
    Long actorUserId,
    BigDecimal shortBurnRate,
    BigDecimal longBurnRate,
    LocalDateTime occurredAt
) {
}
