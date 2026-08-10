package com.richard.fyoung.customerwork.quota;

import com.richard.fyoung.customerwork.counter.WindowCounter;
import com.richard.fyoung.customerwork.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 租户 token 配额的实时判定与记账。
 *
 * <p><b>只判 token，不判金额</b>：金额需要单价表，而单价在 admin 库；让客服端跨库反查
 * 只为算一个实时金额并不划算。token 本就是成本的直接驱动，拦住 token 就拦住了钱。
 * 金额维度走 T+1 账单与预警（{@code cw_tenant_usage_daily}），两者分工明确。</p>
 *
 * <p><b>用量计数与账单是两套数</b>：这里用 {@link WindowCounter} 做实时累加（快、可跨实例共享），
 * 账单以 {@code cw_agent_call_log} 汇总为准（准、可对账）。二者在进程重启且计数器为内存模式时
 * 会短暂不一致——这是刻意取舍：实时链路要的是"快到能拦在调用前"，对账要的是"一分不差"，
 * 用同一份数据满足不了两个目标。生产多副本本就要把计数器切到 Redis，届时重启不丢。</p>
 * @author owlzhangfq@gmail.com
 */
public class TenantQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaGuard.class);

    private static final String KEY_PREFIX = "quota:";

    private final TenantQuotaStore quotaStore;
    private final WindowCounter counter;
    private final boolean enabled;

    public TenantQuotaGuard(TenantQuotaStore quotaStore, WindowCounter counter, boolean enabled) {
        this.quotaStore = quotaStore;
        this.counter = counter;
        this.enabled = enabled;
    }

    /**
     * 模型调用前的配额判定。
     *
     * <p>先判后记：本次预估用量不计入判定基数——预估值本就不准，用它去卡边界会让
     * "刚好卡在限额上"的请求随预估误差随机通过或失败。实际用量在调用后由
     * {@link #record(String, long)} 补记。</p>
     *
     * @param tenantId 租户；为空时取当前上下文
     * @return 判定结果，调用方据此决定放行 / 拒绝 / 降级
     */
    public QuotaDecision check(String tenantId) {
        if (!enabled) {
            return QuotaDecision.allow();
        }
        String tenant = resolveTenant(tenantId);
        if (tenant == null) {
            // 没有租户上下文说明不在多租户链路上（如内部任务），配额无从谈起，放行
            return QuotaDecision.allow();
        }

        for (QuotaPeriod period : QuotaPeriod.values()) {
            Optional<TenantQuota> configured = quotaStore.find(tenant, period);
            if (configured.isEmpty()) {
                continue;
            }
            TenantQuota quota = configured.get();
            if (!quota.enabled() || !quota.hasTokenLimit()) {
                continue;
            }
            long used = counter.current(counterKey(tenant, period), period.retentionSeconds());
            if (used >= quota.tokenLimit()) {
                log.error("tenant token quota exceeded, code={}, tenant={}, period={}, used={}, limit={}, action={}",
                    "QUOTA-TOKEN-EXCEEDED", tenant, period, used, quota.tokenLimit(), quota.exceedAction());
                return QuotaDecision.exceeded(quota, used);
            }
            if (quota.shouldWarn(used)) {
                log.info("tenant token quota warning, tenant={}, period={}, used={}, limit={}",
                    tenant, period, used, quota.tokenLimit());
            }
        }
        return QuotaDecision.allow();
    }

    /**
     * 记录实际用量（模型调用之后）。
     *
     * <p>两个周期各记一份：日配额与月配额是独立的上限，不能共用一个计数。</p>
     */
    public void record(String tenantId, long tokens) {
        if (!enabled || tokens <= 0) {
            return;
        }
        String tenant = resolveTenant(tenantId);
        if (tenant == null) {
            return;
        }
        for (QuotaPeriod period : QuotaPeriod.values()) {
            counter.increment(counterKey(tenant, period), tokens, period.retentionSeconds());
        }
    }

    /** 当前周期已用量（后台展示"额度用了多少"用）。 */
    public long currentUsage(String tenantId, QuotaPeriod period) {
        String tenant = resolveTenant(tenantId);
        return tenant == null ? 0L : counter.current(counterKey(tenant, period), period.retentionSeconds());
    }

    /** 计数键含周期标识（如 {@code quota:acme:MONTHLY:2026-08}），跨周期自然归零，无需显式重置。 */
    private String counterKey(String tenantId, QuotaPeriod period) {
        return KEY_PREFIX + tenantId + ":" + period.name() + ":" + period.periodKey(LocalDate.now());
    }

    private String resolveTenant(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        return TenantContext.get();
    }
}
