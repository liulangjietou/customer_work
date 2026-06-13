package com.example.customerwork.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.mysql.MysqlSession;
import io.agentscope.core.session.redis.RedisSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * 会话持久化配置（对应「多轮会话 & 会话持久化」）。
 *
 * <p>提供一个 {@link Session} Bean 作为状态外置存储，支持四种模式：</p>
 * <ul>
 *   <li><b>memory</b>：进程内，重启丢失，适合本地联调；</li>
 *   <li><b>json</b>：文件落盘，单机重启可恢复；</li>
 *   <li><b>redis</b>：基于 Jedis 的分布式共享存储，多实例共享会话，适合横向扩容；</li>
 *   <li><b>mysql</b>：基于 JDBC（HikariCP 连接池）的持久化存储，强一致、可审计。</li>
 * </ul>
 *
 * <p>{@code CustomerServiceService} 通过 {@code agent.saveTo/loadIfExists} 使用该 Bean，
 * 对底层存储无感知——切换模式只改一行配置。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    @Bean
    public Session agentSession(CustomerWorkProperties properties) {
        return buildSession(properties.getSession());
    }

    /** 按配置构建 Session（抽出以便单测）。 */
    Session buildSession(CustomerWorkProperties.Session cfg) {
        String mode = cfg.getMode() == null ? "memory" : cfg.getMode().trim().toLowerCase();
        switch (mode) {
            case "json": {
                Path dir = Path.of(cfg.getDirectory());
                log.info("会话持久化：JsonSession（目录 {}）", dir.toAbsolutePath());
                return new JsonSession(dir);
            }
            case "redis": {
                CustomerWorkProperties.Session.Redis r = cfg.getRedis();
                log.info("会话持久化：RedisSession（{}:{} keyPrefix={}）",
                    r.getHost(), r.getPort(), r.getKeyPrefix());
                return RedisSession.builder()
                    .jedisClient(buildJedis(r))
                    .keyPrefix(r.getKeyPrefix())
                    .build();
            }
            case "mysql": {
                CustomerWorkProperties.Session.Mysql m = cfg.getMysql();
                log.info("会话持久化：MysqlSession（{}）", m.resolveJdbcUrl());
                return new MysqlSession(buildDataSource(m), m.isAutoCreate());
            }
            default: {
                log.info("会话持久化：InMemorySession（进程内，重启丢失）");
                return new InMemorySession();
            }
        }
    }

    /** 构建 Jedis 客户端（连接惰性建立，构造本身不连接 Redis）。 */
    UnifiedJedis buildJedis(CustomerWorkProperties.Session.Redis r) {
        HostAndPort hostAndPort = new HostAndPort(r.getHost(), r.getPort());
        if (r.getPassword() != null && !r.getPassword().isBlank()) {
            JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .password(r.getPassword())
                .build();
            return new JedisPooled(hostAndPort, clientConfig);
        }
        return new JedisPooled(hostAndPort);
    }

    /** 构建 MySQL DataSource（HikariCP）。用无参构造，连接池在首次取连接时才建立（惰性）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("customer-work-session-pool");
        return ds;
    }
}
