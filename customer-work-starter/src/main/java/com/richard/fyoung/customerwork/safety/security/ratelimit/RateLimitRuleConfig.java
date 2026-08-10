package com.richard.fyoung.customerwork.safety.security.ratelimit;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.ratelimit.mapper.RateLimitRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 限流规则层装配：按 {@code security.rate-limit.store-mode} 选择规则存储，并构建可热刷新的
 * {@link RateLimitRuleProvider}。
 *
 * <p><b>默认关闭即零开销</b>：整个 {@code @Configuration} 由
 * {@code customer-work.security.rate-limit.rule-enabled=true} 门控（照 {@code SensitiveWordConfig} 先例）。
 * 关闭时 Store / Provider 全不装配，{@code RateLimitWebFilter} 拿不到 Provider 便只走全局兜底层——
 * 也就是规则化之前的原有行为，升级不改任何配置即可保持现状。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "customer-work.security.rate-limit", name = "rule-enabled", havingValue = "true")
public class RateLimitRuleConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRuleConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(RateLimitRuleStore.class)
    public RateLimitRuleStore rateLimitRuleStore(CustomerWorkProperties properties,
                                                 ObjectProvider<RateLimitRuleMapper> mapperProvider) {
        String mode = properties.getSecurity().getRateLimit().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("rate-limit rule store: jdbc (MyBatis-Plus, table=cw_rate_limit_rule)");
            return new MybatisRateLimitRuleStore(mapperProvider.getObject());
        }
        log.info("rate-limit rule store: memory (empty rules, use store-mode=jdbc for console-managed rules)");
        return new InMemoryRateLimitRuleStore();
    }

    @Bean
    @ConditionalOnMissingBean(RateLimitRuleProvider.class)
    public RateLimitRuleProvider rateLimitRuleProvider(CustomerWorkProperties properties,
                                                       RateLimitRuleStore store) {
        CustomerWorkProperties.Security.RateLimit cfg = properties.getSecurity().getRateLimit();
        log.info("rate-limit rule provider: refreshEnabled={}, intervalMs={}",
            cfg.isRefreshEnabled(), cfg.getRefreshIntervalMs());
        return new RateLimitRuleProvider(store, cfg.isRefreshEnabled());
    }
}
