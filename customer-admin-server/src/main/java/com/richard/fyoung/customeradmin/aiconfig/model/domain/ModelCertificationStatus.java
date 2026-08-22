package com.richard.fyoung.customeradmin.aiconfig.model.domain;

/** 模型部署认证状态；EXPIRED/STALE 为读取时计算的有效状态。 */
public enum ModelCertificationStatus {
    NOT_REQUIRED,
    UNKNOWN,
    PASSED,
    FAILED,
    EXPIRED,
    STALE
}
