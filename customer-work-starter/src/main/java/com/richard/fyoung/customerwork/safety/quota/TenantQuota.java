package com.richard.fyoung.customerwork.safety.quota;

import java.math.BigDecimal;

/**
 * 租户配额（领域模型）。
 *
 * @param tenantId     租户 ID
 * @param period       周期
 * @param tokenLimit   token 上限，0 = 不限
 * @param amountLimit  金额上限（元），0 = 不限；实时链路只拦 token，金额走 T+1 账单告警
 * @param exceedAction 超额处置
 * @param warnPercent  预警阈值（用量百分比）
 * @param enabled      是否启用
 * @author owlzhangfq@gmail.com
 */
public record TenantQuota(String tenantId,
                          QuotaPeriod period,
                          long tokenLimit,
                          BigDecimal amountLimit,
                          QuotaExceedAction exceedAction,
                          int warnPercent,
                          boolean enabled) {

    /** 是否设置了 token 上限（0 表示不限，不参与判定）。 */
    public boolean hasTokenLimit() {
        return tokenLimit > 0;
    }

    /** 达到预警线但尚未超限。 */
    public boolean shouldWarn(long used) {
        if (!hasTokenLimit() || warnPercent <= 0) {
            return false;
        }
        return used >= tokenLimit * warnPercent / 100L && used < tokenLimit;
    }
}
