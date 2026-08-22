package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

/** 发布任务的评测门禁状态。 */
public enum EvalGateStatus {
    NOT_REQUIRED,
    PENDING,
    PASSED,
    BLOCKED,
    OVERRIDDEN
}
