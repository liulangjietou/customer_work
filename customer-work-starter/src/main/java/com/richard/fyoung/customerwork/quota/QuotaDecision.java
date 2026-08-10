package com.richard.fyoung.customerwork.quota;

/**
 * 配额判定结果。
 *
 * @param allowed 是否放行
 * @param action  超额时的处置方式（放行时为 null）
 * @param used    判定时的已用量
 * @param limit   判定时的上限
 * @param period  触发的周期（放行时为 null）
 * @author owlzhangfq@gmail.com
 */
public record QuotaDecision(boolean allowed,
                            QuotaExceedAction action,
                            long used,
                            long limit,
                            QuotaPeriod period) {

    public static QuotaDecision allow() {
        return new QuotaDecision(true, null, 0L, 0L, null);
    }

    public static QuotaDecision exceeded(TenantQuota quota, long used) {
        return new QuotaDecision(false, quota.exceedAction(), used, quota.tokenLimit(), quota.period());
    }

    /** 是否应当拒绝本次调用（WARN 只记录不拦，DEGRADE 由调用方换模型而非拒绝）。 */
    public boolean shouldBlock() {
        return !allowed && action == QuotaExceedAction.BLOCK;
    }

    /** 是否应当降级到备用模型。 */
    public boolean shouldDegrade() {
        return !allowed && action == QuotaExceedAction.DEGRADE;
    }
}
