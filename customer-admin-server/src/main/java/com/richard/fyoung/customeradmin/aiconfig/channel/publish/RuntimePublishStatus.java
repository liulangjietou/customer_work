package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

/** 运行时配置从本地任务到实例应用的状态。 */
public enum RuntimePublishStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    PARTIAL,
    APPLIED,
    FAILED
}
