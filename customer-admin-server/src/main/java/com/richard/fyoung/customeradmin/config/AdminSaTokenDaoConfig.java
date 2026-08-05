package com.richard.fyoung.customeradmin.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoRedisJackson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Sa-Token 登录态存储装配：把 token / SaSession 从 JVM 内存换到 Redis。
 *
 * <p>Sa-Token 默认的 {@code SaTokenDaoDefaultImpl} 把登录态放在进程内的 Map 里，服务一重启
 * 全部用户被踢下线（"重启后必须重新登录"就是这么来的），多实例部署时各实例也互不认对方签发的
 * token。改存 Redis 后登录态与进程解耦：重启不掉线、可水平扩容，过期交给 Redis 的 TTL
 * （有效期见 {@code sa-token.timeout}，默认 24 小时绝对过期，不按活跃续期）。</p>
 *
 * <p>{@link RedisConnectionFactory} 来自 spring-boot-starter-data-redis 的自动装配（Lettuce，
 * 惰性连接，参数取 {@code spring.data.redis.*}，与 {@link AdminRedisConfig}/
 * {@link AdminDistributedLockConfig} 用的 {@code admin.redis.*} 指向同一个 Redis 实例、同一批
 * 环境变量）。插件自带的自动装配已在 {@code application.yml} 的
 * {@code spring.autoconfigure.exclude} 里关掉，改由这里显式装配，为的是留出
 * {@code admin.sa-token.redis-persistent} 这个开关。</p>
 *
 * <p>注意语义变化：登录态改由 Redis 承载后，Redis 不可达时鉴权是 <b>fail-closed</b> 的
 * （校验登录态直接抛异常 → 请求失败），这正是登录态该有的安全语义，与 {@code ChatHistoryCache}
 * 那种"连不上就退化直读 MySQL"的缓存旁路刻意相反。真出故障时把开关置 false 可临时退回内存态，
 * 代价是重启即全员掉线、多实例互不认 token。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
public class AdminSaTokenDaoConfig {

    /**
     * 登录态持久化 DAO。开关关闭时不注册本 Bean，Sa-Token 自动回退其内置的内存实现。
     */
    @Bean
    @ConditionalOnProperty(name = "admin.sa-token.redis-persistent", havingValue = "true", matchIfMissing = true)
    public SaTokenDao saTokenDao(RedisConnectionFactory redisConnectionFactory) {
        SaTokenDaoRedisJackson dao = new SaTokenDaoRedisJackson();
        // 插件把连接工厂的注入点设计成 init 方法（自动装配时由 @Autowired 触发），这里手动装配
        // 必须显式调一次，否则内部两个 RedisTemplate 为 null，要到第一次鉴权才 NPE。
        dao.init(redisConnectionFactory);
        log.info("Sa-Token login state persisted to Redis, restart will not kick users out");
        return dao;
    }
}
