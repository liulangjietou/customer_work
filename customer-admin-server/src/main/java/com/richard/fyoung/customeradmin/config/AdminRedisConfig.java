package com.richard.fyoung.customeradmin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * 历史对话列表读缓存（{@code ChatHistoryCache}）用的 {@link JedisPool}。仅用于降低
 * {@code GET /workspace/{agentCode}/chat/sessions} 系列只读接口对 MySQL 的读压力，权威数据源
 * 始终是 {@code MysqlAgentStateStore}（见 {@link AdminAgentRuntimeConfig}）——不改写路径，不引入
 * Redis→MySQL 的刷盘/一致性问题。构造 {@link JedisPool} 本身是惰性连接（不在此处真正连 Redis），
 * 与 {@code customer-work-starter} 的 {@code SessionConfig#buildJedisPool} 同一手法。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AdminRedisConfig {

    @Value("${admin.redis.host}")
    private String host;

    @Value("${admin.redis.port}")
    private int port;

    @Value("${admin.redis.password}")
    private String password;

    @Bean
    public JedisPool chatHistoryJedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        // commons-pool2 默认给每个 GenericObjectPool 注册一个固定名字的 JMX MBean（jmxEnabled 默认
        // true），同一 JVM 内（比如开发期反复重启/热重载）再建一次就会因为 MBean 重名抛
        // UnableToRegisterMBeanException 导致启动失败——这只是一个内部缓存连接池，不需要 JMX 监控，
        // 直接关掉，从根上消除命名冲突的可能性。
        poolConfig.setJmxEnabled(false);
        // Docker Desktop(macOS) 的端口代理会偶发掐断到容器 Redis 的连接（表现为借出/新建连接时
        // Unexpected end of stream，本地实测频繁复现）。三管齐下：
        // 1) testOnBorrow：借出前 PING 校验，坏连接直接剔除重建，不流入业务；
        // 2) testWhileIdle + 定期驱逐：空闲连接后台校验，被代理静默断掉的连接及时清理；
        // 3) minIdle=1：evictor 维持一条已验证的常驻连接，降低每次请求现场新建连接（该路径
        //    正是握手被掐的高发点）的频率。
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinIdle(1);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(60));
        if (password != null && !password.isBlank()) {
            return new JedisPool(poolConfig, host, port, 2000, password);
        }
        return new JedisPool(poolConfig, host, port);
    }
}
