package com.richard.fyoung.customeradmin.slo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** 新增或更新 SLO 策略；目标值使用 0..1 小数。 */
public record SloPolicySaveRequest(
    Long id,
    @NotBlank String policyName,
    @NotBlank String scopeType,
    String scopeKey,
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
    BigDecimal availabilityTarget,
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
    BigDecimal latencyTarget,
    @NotNull @Min(1) Long latencyThresholdMs,
    @NotNull @Min(1) Integer shortWindowMinutes,
    @NotNull @Min(2) Integer longWindowMinutes,
    @Min(1) Integer minimumSampleCount,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal burnRateThreshold,
    Boolean enabled
) {
}
