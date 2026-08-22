package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 创建下一不可变版本；已有版本没有更新接口。 */
public record ModelRouteVersionCreateRequest(
    String changeNote,
    @NotEmpty(message = "rules 不能为空") List<@Valid ModelRouteRuleRequest> rules) {
}
