package com.richard.fyoung.customeradmin.aiconfig.model.domain;

/** 模型健康路由人工覆盖模式。强制覆盖必须有到期时间，避免永久掩盖真实探测状态。 */
public enum ModelHealthOverrideMode {
    AUTO,
    FORCE_HEALTHY,
    FORCE_UNHEALTHY
}
