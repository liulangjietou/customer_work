package com.richard.fyoung.customerwork.safety.quota;

import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户配额判定单测：关闭时放行、无配额放行、超限按策略处置、日月两周期独立、跨租户不串。
 * @author owlzhangfq@gmail.com
 */
class TenantQuotaGuardTest {

    private final InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore();
    private final InMemoryWindowCounter counter = new InMemoryWindowCounter();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TenantQuotaGuard guard(boolean enabled) {
        return new TenantQuotaGuard(store, counter, enabled);
    }

    private TenantQuota quota(String tenant, QuotaPeriod period, long limit, QuotaExceedAction action) {
        return new TenantQuota(tenant, period, limit, BigDecimal.ZERO, action, 80, true);
    }

    @Test
    void check_shouldAllow_whenDisabled() {
        store.save(quota("acme", QuotaPeriod.DAILY, 1, QuotaExceedAction.BLOCK));
        TenantQuotaGuard g = guard(false);
        g.record("acme", 1000);
        assertTrue(g.check("acme").allowed(), "配额关闭时一律放行，且不记账");
    }

    @Test
    void check_shouldAllow_whenNoQuotaConfigured() {
        assertTrue(guard(true).check("no-quota-tenant").allowed(), "没配配额等于不限");
    }

    @Test
    void check_shouldAllow_whenNoTenantContext() {
        // 内部任务、启动初始化等链路没有租户，配额无从谈起
        assertTrue(guard(true).check(null).allowed(), "无租户上下文应放行而不是报错");
    }

    @Test
    void check_shouldBlock_whenTokenLimitReached() {
        store.save(quota("acme", QuotaPeriod.DAILY, 100, QuotaExceedAction.BLOCK));
        TenantQuotaGuard g = guard(true);
        g.record("acme", 100);

        QuotaDecision decision = g.check("acme");
        assertFalse(decision.allowed(), "达到上限应拒绝");
        assertTrue(decision.shouldBlock(), "BLOCK 策略应拦截");
        assertFalse(decision.shouldDegrade(), "BLOCK 策略不是降级");
        assertEquals(QuotaPeriod.DAILY, decision.period(), "应指出是哪个周期触发的");
    }

    @Test
    void check_shouldDegrade_whenActionIsDegrade() {
        store.save(quota("acme", QuotaPeriod.DAILY, 10, QuotaExceedAction.DEGRADE));
        TenantQuotaGuard g = guard(true);
        g.record("acme", 10);

        QuotaDecision decision = g.check("acme");
        assertTrue(decision.shouldDegrade(), "DEGRADE 策略应降级");
        assertFalse(decision.shouldBlock(), "降级不等于拦截——服务要继续，只是换个便宜模型");
    }

    @Test
    void check_shouldNotBlock_whenActionIsWarn() {
        store.save(quota("acme", QuotaPeriod.DAILY, 10, QuotaExceedAction.WARN));
        TenantQuotaGuard g = guard(true);
        g.record("acme", 100);

        QuotaDecision decision = g.check("acme");
        assertFalse(decision.allowed(), "超限事实要如实反映");
        assertFalse(decision.shouldBlock(), "WARN 只记录不拦");
        assertFalse(decision.shouldDegrade(), "WARN 也不降级");
    }

    @Test
    void record_shouldCountDailyAndMonthlyIndependently() {
        store.save(quota("acme", QuotaPeriod.DAILY, 1000, QuotaExceedAction.BLOCK));
        store.save(quota("acme", QuotaPeriod.MONTHLY, 100, QuotaExceedAction.BLOCK));
        TenantQuotaGuard g = guard(true);
        g.record("acme", 100);

        // 日额度还剩很多，但月额度已满——两个周期是独立上限，不能共用一份计数
        assertFalse(g.check("acme").allowed(), "月配额触顶就该拦，哪怕日配额还很富余");
        assertEquals(100L, g.currentUsage("acme", QuotaPeriod.DAILY), "日用量应独立计数");
        assertEquals(100L, g.currentUsage("acme", QuotaPeriod.MONTHLY), "月用量应独立计数");
    }

    @Test
    void record_shouldIsolateTenants() {
        store.save(quota("acme", QuotaPeriod.DAILY, 100, QuotaExceedAction.BLOCK));
        store.save(quota("other", QuotaPeriod.DAILY, 100, QuotaExceedAction.BLOCK));
        TenantQuotaGuard g = guard(true);
        g.record("acme", 100);

        assertFalse(g.check("acme").allowed(), "用超的租户应被拦");
        assertTrue(g.check("other").allowed(), "另一个租户的额度不该被消耗");
    }

    @Test
    void check_shouldFallBackToTenantContext() {
        store.save(quota("ctx-tenant", QuotaPeriod.DAILY, 10, QuotaExceedAction.BLOCK));
        TenantQuotaGuard g = guard(true);
        TenantContext.set("ctx-tenant");
        g.record(null, 10);

        assertFalse(g.check(null).allowed(), "未显式传租户时应取当前上下文");
    }

    @Test
    void check_shouldIgnoreDisabledQuota() {
        store.save(new TenantQuota("acme", QuotaPeriod.DAILY, 1, BigDecimal.ZERO,
            QuotaExceedAction.BLOCK, 80, false));
        TenantQuotaGuard g = guard(true);
        g.record("acme", 100);
        assertTrue(g.check("acme").allowed(), "停用的配额不参与判定");
    }

    @Test
    void check_shouldIgnoreZeroLimit() {
        store.save(quota("acme", QuotaPeriod.DAILY, 0, QuotaExceedAction.BLOCK));
        TenantQuotaGuard g = guard(true);
        g.record("acme", 100);
        assertTrue(g.check("acme").allowed(), "上限 0 表示不限，而非一点额度都不给");
    }

    @Test
    void shouldWarn_shouldTriggerBeforeLimit() {
        TenantQuota q = quota("acme", QuotaPeriod.DAILY, 100, QuotaExceedAction.BLOCK);
        assertFalse(q.shouldWarn(79), "未到阈值不预警");
        assertTrue(q.shouldWarn(80), "达到 80% 应预警");
        assertFalse(q.shouldWarn(100), "已超限就不是预警而是拦截了");
    }
}
