package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 新建策略并一次性写入第一个不可变版本。 */
public record ModelRoutePolicyCreateRequest(
    @NotBlank(message = "policyCode 不能为空") String policyCode,
    @NotBlank(message = "policyName 不能为空") String policyName,
    String description,
    String changeNote,
    @NotEmpty(message = "rules 不能为空") List<@Valid ModelRouteRuleRequest> rules) {
}
