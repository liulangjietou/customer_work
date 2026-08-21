package com.richard.fyoung.customerwork.safety.quota;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内配额存储（默认实现，单测与未接库时用）。
 * @author owlzhangfq@gmail.com
 */
public class InMemoryTenantQuotaStore implements TenantQuotaStore {

    private final Map<String, TenantQuota> quotas = new ConcurrentHashMap<>();

    @Override
    public Optional<TenantQuota> find(String tenantId, QuotaPeriod period) {
        return Optional.ofNullable(quotas.get(key(tenantId, period)));
    }

    @Override
    public List<TenantQuota> findByTenant(String tenantId) {
        List<TenantQuota> result = new ArrayList<>();
        for (QuotaPeriod period : QuotaPeriod.values()) {
            find(tenantId, period).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public void save(TenantQuota quota) {
        quotas.put(key(quota.tenantId(), quota.period()), quota);
    }

    @Override
    public void delete(String tenantId, QuotaPeriod period) {
        quotas.remove(key(tenantId, period));
    }

    private String key(String tenantId, QuotaPeriod period) {
        return TenantContext.normalizedTenantKey(tenantId) + ":" + period.name();
    }
}
