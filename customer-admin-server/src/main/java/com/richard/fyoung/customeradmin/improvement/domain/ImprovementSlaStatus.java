package com.richard.fyoung.customeradmin.improvement.domain;

/** SLA 是派生状态，避免时间流逝却要靠任务改写一列。 */
public enum ImprovementSlaStatus {
    ON_TRACK,
    OVERDUE,
    CLOSED
}
