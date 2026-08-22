package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

/** 运行时配置从本地任务到实例应用的状态。 */
public enum RuntimePublishStatus {
    PENDING,
    PROCESSING,
    /** 评测门禁确定性阻断；不自动重试，等待重评或有审计的紧急豁免。 */
    BLOCKED,
    PUBLISHED,
    PARTIAL,
    APPLIED,
    /** 任务固化快照已被同一 Nacos 键的更新发布意图取代。 */
    SUPERSEDED,
    FAILED
}
