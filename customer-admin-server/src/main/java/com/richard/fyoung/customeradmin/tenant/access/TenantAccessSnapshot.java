package com.richard.fyoung.customeradmin.tenant.access;

import java.time.LocalDateTime;

/** 登录态和运行时发布共用的租户访问状态快照。 */
public record TenantAccessSnapshot(
    String tenantId,
    String status,
    long accessEpoch,
    LocalDateTime expireTime
) {
}
