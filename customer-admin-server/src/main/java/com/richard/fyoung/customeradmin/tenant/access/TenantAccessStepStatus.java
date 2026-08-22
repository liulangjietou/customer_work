package com.richard.fyoung.customeradmin.tenant.access;

/** 租户访问编排中同步步骤的可核验状态。 */
public enum TenantAccessStepStatus {
    NOT_REQUIRED,
    EPOCH_ENFORCED,
    COMPLETED
}
