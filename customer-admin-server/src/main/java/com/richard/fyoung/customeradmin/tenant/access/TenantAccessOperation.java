package com.richard.fyoung.customeradmin.tenant.access;

/** 触发访问快照发布的领域命令。 */
public enum TenantAccessOperation {
    PROVISION,
    EXPIRY_CHANGE,
    STATUS_CHANGE,
    SESSION_REVOKE,
    OFFBOARD
}
