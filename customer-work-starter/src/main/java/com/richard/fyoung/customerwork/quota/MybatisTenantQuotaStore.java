package com.richard.fyoung.customerwork.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.quota.entity.TenantQuotaDO;
import com.richard.fyoung.customerwork.quota.mapper.TenantQuotaMapper;
import com.richard.fyoung.customerwork.tenant.CrossTenantOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 配额的 MyBatis-Plus 实现。
 *
 * <p>查询走 {@link CrossTenantOperations}：配额判定发生在请求链路上，那时上下文里的租户
 * 正是要判定的对象，本该能自动过滤到。但配额也会被<b>运营方跨租户读写</b>（后台配额度、
 * 定时任务批量核对），两种调用方都用同一个 Store，让 Store 自己按显式 tenantId 取数
 * 比让调用方各自记得切上下文更不容易出错。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisTenantQuotaStore implements TenantQuotaStore {

    private final TenantQuotaMapper mapper;

    public MybatisTenantQuotaStore(TenantQuotaMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TenantQuota> find(String tenantId, QuotaPeriod period) {
        TenantQuotaDO row = CrossTenantOperations.execute(() -> mapper.selectOne(
            new LambdaQueryWrapper<TenantQuotaDO>()
                .eq(TenantQuotaDO::getTenantId, tenantId)
                .eq(TenantQuotaDO::getPeriod, period.name())));
        return Optional.ofNullable(row).map(MybatisTenantQuotaStore::toDomain);
    }

    @Override
    public List<TenantQuota> findByTenant(String tenantId) {
        List<TenantQuotaDO> rows = CrossTenantOperations.execute(() -> mapper.selectList(
            new LambdaQueryWrapper<TenantQuotaDO>().eq(TenantQuotaDO::getTenantId, tenantId)));
        return rows.stream().map(MybatisTenantQuotaStore::toDomain).toList();
    }

    @Override
    public void save(TenantQuota quota) {
        long now = System.currentTimeMillis();
        CrossTenantOperations.run(() -> {
            TenantQuotaDO existing = mapper.selectOne(new LambdaQueryWrapper<TenantQuotaDO>()
                .eq(TenantQuotaDO::getTenantId, quota.tenantId())
                .eq(TenantQuotaDO::getPeriod, quota.period().name()));

            TenantQuotaDO row = existing == null ? new TenantQuotaDO() : existing;
            row.setTenantId(quota.tenantId());
            row.setPeriod(quota.period().name());
            row.setTokenLimit(quota.tokenLimit());
            row.setAmountLimit(quota.amountLimit() == null ? BigDecimal.ZERO : quota.amountLimit());
            row.setExceedAction(quota.exceedAction().name());
            row.setWarnPercent(quota.warnPercent());
            row.setEnabled(quota.enabled() ? 1 : 0);
            row.setUpdatedAtMs(now);
            if (existing == null) {
                row.setCreatedAtMs(now);
                mapper.insert(row);
            } else {
                mapper.updateById(row);
            }
        });
    }

    @Override
    public void delete(String tenantId, QuotaPeriod period) {
        CrossTenantOperations.run(() -> mapper.delete(new LambdaQueryWrapper<TenantQuotaDO>()
            .eq(TenantQuotaDO::getTenantId, tenantId)
            .eq(TenantQuotaDO::getPeriod, period.name())));
    }

    private static TenantQuota toDomain(TenantQuotaDO row) {
        return new TenantQuota(
            row.getTenantId(),
            QuotaPeriod.parse(row.getPeriod()),
            row.getTokenLimit() == null ? 0L : row.getTokenLimit(),
            row.getAmountLimit() == null ? BigDecimal.ZERO : row.getAmountLimit(),
            QuotaExceedAction.parse(row.getExceedAction()),
            row.getWarnPercent() == null ? 0 : row.getWarnPercent(),
            row.getEnabled() == null || row.getEnabled() == 1);
    }
}
