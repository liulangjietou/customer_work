package com.richard.fyoung.customeradmin.improvement.dto;

/** 复评备注；类型已在制品绑定时冻结，不能在复评按钮上临时换口径。 */
public record ImprovementReevaluateRequest(String remark) {
}
