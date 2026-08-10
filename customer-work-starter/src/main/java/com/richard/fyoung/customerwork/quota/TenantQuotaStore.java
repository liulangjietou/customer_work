package com.richard.fyoung.customerwork.quota;

import java.util.List;
import java.util.Optional;

/**
 * 租户配额存储 SPI（第 9 次套用 Store SPI 模式：接口 + InMemory 默认 + MyBatis-Plus 实现）。
 * @author owlzhangfq@gmail.com
 */
public interface TenantQuotaStore {

    /** 查某租户某周期的配额；未配置返回 empty（= 不限）。 */
    Optional<TenantQuota> find(String tenantId, QuotaPeriod period);

    /** 某租户的全部周期配额。 */
    List<TenantQuota> findByTenant(String tenantId);

    /** 保存或更新（按 tenantId + period 唯一）。 */
    void save(TenantQuota quota);

    /** 删除某租户某周期的配额。 */
    void delete(String tenantId, QuotaPeriod period);
}
