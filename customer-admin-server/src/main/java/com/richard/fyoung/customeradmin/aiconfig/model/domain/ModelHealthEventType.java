package com.richard.fyoung.customeradmin.aiconfig.model.domain;

/** 模型健康追加事件类型。 */
public enum ModelHealthEventType {
    PROBE,
    STATE_TRANSITION,
    STALE_PROBE,
    OVERRIDE_SET,
    OVERRIDE_CLEARED,
    OVERRIDE_EXPIRED
}
