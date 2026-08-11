package com.richard.fyoung.customerwork.infra.counter;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.richard.fyoung.customerwork.infra.config.properties.DistributedProperties;

/**
 * 窗口计数器装配：按 {@code customer-work.distributed.counter-mode} 选实现。
 *
 * <p>下游可声明自己的 {@link WindowCounter} Bean 覆盖（如换成 Bucket4j），与 starter 其它 SPI 一致。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class WindowCounterConfig {

    private static final Logger log = LoggerFactory.getLogger(WindowCounterConfig.class);

    private static final String MODE_REDIS = "redis";

    @Bean
    @ConditionalOnMissingBean
    public WindowCounter windowCounter(CustomerWorkProperties properties,
                                       ObjectProvider<RedissonClient> redissonProvider) {
        DistributedProperties cfg = properties.getDistributed();
        InMemoryWindowCounter inMemory = new InMemoryWindowCounter();
        if (!MODE_REDIS.equalsIgnoreCase(cfg.getCounterMode())) {
            return inMemory;
        }
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            // 配了 redis 却没有客户端可用，说明装配缺了一环。这里让位给进程内实现而不是启动失败：
            // 限流/熔断是旁路保护，不该拖垮主链路的可启动性；配置没生效这件事由 error 日志暴露。
            log.error("counter-mode=redis but no RedissonClient available, fallback to in-memory, code={}",
                "COUNTER-REDIS-CLIENT-MISSING");
            return inMemory;
        }
        log.info("distributed window counter enabled, prefix={}", cfg.getCounterKeyPrefix());
        return new RedissonWindowCounter(redisson, cfg.getCounterKeyPrefix(), inMemory);
    }
}
