package com.richard.fyoung.customeradmin.slo.dto;

/** 当前租户活跃 SLO 告警汇总，不受明细分页窗口影响。 */
public record SloAlertSummaryVO(long openCount, long acknowledgedCount) {
}
