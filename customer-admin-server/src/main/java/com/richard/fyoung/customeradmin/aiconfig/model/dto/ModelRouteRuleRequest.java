package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 路由版本规则输入。 */
public record ModelRouteRuleRequest(
    @NotBlank(message = "purpose 不能为空") String purpose,
    @NotNull(message = "deploymentId 不能为空") Long deploymentId,
    @NotNull(message = "priority 不能为空") @Min(value = 0, message = "priority 不能小于 0") Integer priority,
    @Valid ModelRouteCondition condition) {
}
