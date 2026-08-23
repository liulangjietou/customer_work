package com.richard.fyoung.customeradmin.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 自然日/自然月金额消耗预测。 */
@Data
public class CostForecastVO {
    private String tenantId;
    private String period;
    private String periodKey;
    private LocalDate periodStart;
    private LocalDate asOfDate;
    private int elapsedDays;
    private int totalDays;
    private BigDecimal usedAmount;
    private BigDecimal averageDailyAmount;
    private BigDecimal forecastAmount;
    private BigDecimal amountLimit;
    private BigDecimal utilizationPercent;
    private boolean forecastExceeded;
}
