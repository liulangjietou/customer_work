package com.richard.fyoung.customerwork.data.outbox;

/** 数据库 Outbox 状态。 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    ABANDONED
}
