package com.richard.fyoung.customerwork.sensitiveword;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 敏感词过滤装配：按 {@code sensitive-word.store-mode} 选择词表存储实现，并构建可热重建的 {@link SensitiveWordFilter}。
 *
 * <p><b>默认关闭即零开销：</b>整个 {@code @Configuration} 用 {@code @ConditionalOnProperty} 只在
 * {@code customer-work.sensitive-word.enabled=true} 时装配（照 XxlJobSchedulerConfig / SyntheticMonitor 先例）——
 * enabled=false 时 Store / Filter / 中间件全不装配，启动期<b>零 IO（不查 DB）、零 AC 构建、零日志</b>。</p>
 *
 * <p>存储：默认 {@link InMemorySensitiveWordStore}（进程内带演示种子）；{@code jdbc} 落
 * {@link MybatisSensitiveWordStore}（复用 {@code CustomerWorkPersistenceConfig} 独立持久化环境）。
 * {@link SensitiveWordMapper} 用 {@link ObjectProvider} 惰性获取：memory 模式不装配 Mapper 也不报错。
 * 下游声明自己的 {@link SensitiveWordStore} / {@link SensitiveWordFilter} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "customer-work.sensitive-word", name = "enabled", havingValue = "true")
public class SensitiveWordConfig {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(SensitiveWordStore.class)
    public SensitiveWordStore sensitiveWordStore(CustomerWorkProperties properties,
                                                 ObjectProvider<SensitiveWordMapper> mapperProvider) {
        String mode = properties.getSensitiveWord().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("sensitive-word store: jdbc (MyBatis-Plus, table=cw_sensitive_word)");
            return new MybatisSensitiveWordStore(mapperProvider.getObject());
        }
        log.info("sensitive-word store: memory (in-process demo seeds, use store-mode=jdbc in production)");
        return new InMemorySensitiveWordStore();
    }

    @Bean
    @ConditionalOnMissingBean(SensitiveWordFilter.class)
    public SensitiveWordFilter sensitiveWordFilter(CustomerWorkProperties properties, SensitiveWordStore store) {
        CustomerWorkProperties.SensitiveWord cfg = properties.getSensitiveWord();
        return new SensitiveWordFilter(store, cfg.resolveMaskChar(), cfg.getDefaultAction());
    }
}
