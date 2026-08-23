package com.richard.fyoung.customeradmin.tenant.access;

/** 租户访问快照可靠发布状态。 */
public enum TenantAccessPublishStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    SUPERSEDED,
    FAILED
}
