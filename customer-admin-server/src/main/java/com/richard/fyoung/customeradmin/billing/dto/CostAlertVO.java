package com.richard.fyoung.customeradmin.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 金额预算告警展示对象。 */
@Data
public class CostAlertVO {
    private Long id;
    private String tenantId;
    private String period;
    private String periodKey;
    private String alertType;
    private BigDecimal usedAmount;
    private BigDecimal limitAmount;
    private BigDecimal forecastAmount;
    private String status;
    private LocalDateTime firstSeenAt;
    private Long ackBy;
    private LocalDateTime ackAt;
}
