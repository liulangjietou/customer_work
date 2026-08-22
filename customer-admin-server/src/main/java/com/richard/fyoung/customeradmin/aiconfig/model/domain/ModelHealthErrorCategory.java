package com.richard.fyoung.customeradmin.aiconfig.model.domain;

/** 模型探测失败分类。 */
public enum ModelHealthErrorCategory {
    AUTH,
    RATE_LIMIT,
    TIMEOUT,
    CONTRACT,
    UNKNOWN
}
