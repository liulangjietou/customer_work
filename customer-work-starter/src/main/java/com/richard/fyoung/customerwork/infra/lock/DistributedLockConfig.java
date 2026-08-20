package com.richard.fyoung.customerwork.infra.lock;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.richard.fyoung.customerwork.infra.config.properties.DistributedLockProperties;
import com.richard.fyoung.customerwork.infra.config.properties.DistributedProperties;

/**
 * 分布式锁自动装配：随 {@code CustomerWorkInfraAutoConfiguration} 的组件扫描一并生效，下游应用引入
 * starter 依赖即可直接注入 {@link DistributedLockExecutor} 使用，无需关心 Redisson 接线细节。
 *
 * <p>{@code customer-admin-server} 关闭了 starter 自动装配（见其 {@code spring.autoconfigure.exclude}），
 * 需要本能力时在自己的 {@code @Configuration} 里仿此手动 new，复用自身已有的 Redis 连接配置——
 * 与 Store SPI / AgentStateStore 等既有能力的接入方式一致。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class DistributedLockConfig {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockConfig.class);

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(CustomerWorkProperties properties) {
        return buildRedissonClient(properties.getDistributedLock().getRedis());
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockExecutor distributedLockExecutor(RedissonClient redissonClient) {
        return new RedissonDistributedLockExecutor(redissonClient);
    }

    /**
     * 会话串行锁：按 {@code customer-work.distributed.session-lock-mode} 选实现。
     *
     * <p>默认进程内——它只在网关按会话 sticky 路由时才正确，但那正是当前部署形态；
     * 切 redis 后即使同一会话落到不同实例也能互斥。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public SessionLock sessionLock(CustomerWorkProperties properties, RedissonClient redissonClient) {
        DistributedProperties cfg = properties.getDistributed();
        InMemorySessionLock inMemory = new InMemorySessionLock(cfg.getSessionLockWaitSeconds());
        if (!StoreModes.isRedis(cfg.getSessionLockMode())) {
            return inMemory;
        }
        log.info("distributed session lock enabled, waitSeconds={}, leaseSeconds={}",
            cfg.getSessionLockWaitSeconds(), cfg.getSessionLockLeaseSeconds());
        return new RedissonSessionLock(redissonClient, "cw:sessionlock:",
            cfg.getSessionLockWaitSeconds(), cfg.getSessionLockLeaseSeconds(), inMemory);
    }

    /**
     * 按 host/port/password 构建单机模式 RedissonClient（抽出静态方法，供 admin-server 手动装配时复用同一套逻辑）。
     *
     * <p>当前用 {@code useSingleServer()} 对接单实例 Redis；生产环境若切成哨兵/集群部署，只需把这一处
     * 换成 {@code config.useSentinelServers()...} / {@code config.useClusterServers()...}，调用方
     * （{@code PermissionService} 等业务代码、admin-server 的手动装配）完全无感知，不用改。</p>
     */
    public static RedissonClient buildRedissonClient(DistributedLockProperties.Redis r) {
        Config config = new Config();
        // 惰性连接：与本项目 JedisPool 的既有约定一致（构造本身不真正连 Redis），Redisson 默认在
        // create() 时就同步建连、连不上直接抛异常，会导致 Redis 不可达时整个应用直接启动失败——
        // 而分布式锁只是保护单个并发场景的旁路能力，不该拖垮主链路的可启动性；真正连接延后到
        // 第一次 tryLock 才发生，届时连不上就在那次调用里失败，由调用方按业务语义处理（见
        // RedissonDistributedLockExecutor 对基础设施异常"不吞、原样上抛"的注释）。
        config.setLazyInitialization(true);
        SingleServerConfig serverConfig = config.useSingleServer()
            .setAddress("redis://" + r.getHost() + ":" + r.getPort());
        if (r.getPassword() != null && !r.getPassword().isBlank()) {
            serverConfig.setPassword(r.getPassword());
        }
        return Redisson.create(config);
    }
}
