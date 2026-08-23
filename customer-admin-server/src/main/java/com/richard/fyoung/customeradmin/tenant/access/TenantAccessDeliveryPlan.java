package com.richard.fyoung.customeradmin.tenant.access;

/** 写入可靠发布任务的同步编排结果；运行时投递状态由任务状态机继续推进。 */
public record TenantAccessDeliveryPlan(
    TenantAccessOperation operation,
    TenantAccessStepStatus sessionRevocationStatus,
    TenantAccessStepStatus channelDisableStatus,
    int channelsDisabledCount
) {

    public static TenantAccessDeliveryPlan provision() {
        return new TenantAccessDeliveryPlan(TenantAccessOperation.PROVISION,
            TenantAccessStepStatus.NOT_REQUIRED, TenantAccessStepStatus.NOT_REQUIRED, 0);
    }

    public static TenantAccessDeliveryPlan expiryChange() {
        return revocation(TenantAccessOperation.EXPIRY_CHANGE);
    }

    public static TenantAccessDeliveryPlan statusChange() {
        return revocation(TenantAccessOperation.STATUS_CHANGE);
    }

    public static TenantAccessDeliveryPlan sessionRevoke() {
        return revocation(TenantAccessOperation.SESSION_REVOKE);
    }

    public static TenantAccessDeliveryPlan offboard(int channelsDisabledCount) {
        return new TenantAccessDeliveryPlan(TenantAccessOperation.OFFBOARD,
            TenantAccessStepStatus.EPOCH_ENFORCED, TenantAccessStepStatus.COMPLETED,
            channelsDisabledCount);
    }

    private static TenantAccessDeliveryPlan revocation(TenantAccessOperation operation) {
        return new TenantAccessDeliveryPlan(operation, TenantAccessStepStatus.EPOCH_ENFORCED,
            TenantAccessStepStatus.NOT_REQUIRED, 0);
    }
}
