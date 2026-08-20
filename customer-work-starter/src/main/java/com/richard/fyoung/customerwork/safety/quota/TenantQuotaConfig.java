package com.richard.fyoung.customerwork.safety.quota;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import com.richard.fyoung.customerwork.safety.quota.mapper.TenantQuotaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.richard.fyoung.customerwork.infra.config.properties.QuotaProperties;

/**
 * 租户配额装配：Store 按 {@code store-mode} 选实现，Guard 按 {@code enabled} 决定是否真的拦。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class TenantQuotaConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public TenantQuotaStore tenantQuotaStore(CustomerWorkProperties properties,
                                             ObjectProvider<TenantQuotaMapper> mapperProvider) {
        QuotaProperties cfg = properties.getQuota();
        if (!StoreModes.isJdbc(cfg.getStoreMode())) {
            return new InMemoryTenantQuotaStore();
        }
        TenantQuotaMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            // 配了 jdbc 却没有 Mapper（持久层未装配），让位给内存实现而不是启动失败：
            // 配额是旁路保护，不该拖垮主链路可启动性；配置没生效由 error 日志暴露
            log.error("quota store-mode=jdbc but TenantQuotaMapper unavailable, fallback to in-memory, code={}",
                "QUOTA-MAPPER-MISSING");
            return new InMemoryTenantQuotaStore();
        }
        log.info("tenant quota store ready (jdbc)");
        return new MybatisTenantQuotaStore(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantQuotaGuard tenantQuotaGuard(CustomerWorkProperties properties,
                                             TenantQuotaStore quotaStore,
                                             ObjectProvider<WindowCounter> counterProvider) {
        WindowCounter counter = counterProvider.getIfAvailable();
        boolean enabled = properties.getQuota().isEnabled();
        if (enabled) {
            log.info("tenant quota guard enabled, storeMode={}", properties.getQuota().getStoreMode());
        }
        return new TenantQuotaGuard(quotaStore,
            counter == null ? new InMemoryWindowCounter() : counter, enabled);
    }
}
