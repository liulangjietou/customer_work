package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.lock.DistributedLockConfig;
import com.richard.fyoung.customerwork.infra.lock.DistributedLockExecutor;
import com.richard.fyoung.customerwork.infra.lock.RedissonDistributedLockExecutor;
import com.richard.fyoung.customerwork.infra.lock.RedissonSessionLock;
import com.richard.fyoung.customerwork.infra.lock.SessionLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.richard.fyoung.customerwork.infra.config.properties.DistributedLockProperties;

/**
 * 分布式锁装配（仿 {@link AdminRedisConfig}/{@link AdminAgentRuntimeConfig} 手法）：本模块已
 * {@code spring.autoconfigure.exclude} 关闭 starter 自动装配，故 starter 的
 * {@code DistributedLockConfig} 不会加载，这里手动 new，复用本模块已有的 {@code admin.redis.*}
 * 配置（与 {@link AdminRedisConfig} 指向同一个 Redis 实例，物理上不新开一路连接配置），
 * 直接调用 starter 暴露的 {@link DistributedLockConfig#buildRedissonClient} 复用同一套接线逻辑。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AdminDistributedLockConfig {

    @Value("${admin.redis.host}")
    private String host;

    @Value("${admin.redis.port}")
    private int port;

    @Value("${admin.redis.password}")
    private String password;

    @Value("${admin.agent-runtime.session-lock-wait-seconds:10}")
    private long sessionLockWaitSeconds;

    @Value("${admin.agent-runtime.session-lock-lease-seconds:1800}")
    private long sessionLockLeaseSeconds;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        DistributedLockProperties.Redis r = new DistributedLockProperties.Redis();
        r.setHost(host);
        r.setPort(port);
        r.setPassword(password);
        return DistributedLockConfig.buildRedissonClient(r);
    }

    @Bean
    public DistributedLockExecutor distributedLockExecutor(RedissonClient redissonClient) {
        return new RedissonDistributedLockExecutor(redissonClient);
    }

    /**
     * Admin 对话按 tenant + agent + session 跨 Pod 串行。Redis 故障时必须失败关闭，
     * 否则退化为每 Pod 一把本地锁会制造“看似加锁、实际并发写历史”的数据损坏。
     */
    @Bean
    public SessionLock adminSessionLock(RedissonClient redissonClient) {
        return new RedissonSessionLock(redissonClient, "cw:admin:sessionlock:",
            sessionLockWaitSeconds, sessionLockLeaseSeconds, null, false);
    }
}
