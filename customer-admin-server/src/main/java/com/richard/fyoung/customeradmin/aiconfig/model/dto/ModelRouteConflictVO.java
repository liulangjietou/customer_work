package com.richard.fyoung.customeradmin.aiconfig.model.dto;

/** 路由规则冲突；ruleIndex 使用请求数组的零基下标，便于前端定位。 */
public record ModelRouteConflictVO(String code,
                                   Integer ruleIndex,
                                   Integer conflictingRuleIndex,
                                   String message) {
}
