package com.richard.fyoung.customeradmin.businessoutcome.dto;

/** 指标可用性，防止缺失数据被零值掩盖。 */
public record MetricAvailability(String status, String reason) {
}
