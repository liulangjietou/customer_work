package com.richard.fyoung.customerwork.safety.tenant;

/** 控制面与运行时共用的租户访问快照协议常量。 */
public final class TenantAccessConstants {

    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_DATA_ID = "customer-work-tenant-access";
    /** 登录态与终端 JWT 共用的租户访问版本字段名。 */
    public static final String ACCESS_EPOCH_KEY = "tenantAccessEpoch";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_TERMINATED = "TERMINATED";

    private TenantAccessConstants() {
    }
}
