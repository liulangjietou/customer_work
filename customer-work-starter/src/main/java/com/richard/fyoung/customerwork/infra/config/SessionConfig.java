package com.richard.fyoung.customerwork.infra.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.redis.state.jedis.JedisAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * 会话 / 状态持久化配置（对应「多轮会话 &amp; 会话持久化」，AgentScope 2.0 迁移版）。
 *
 * <p>2.0 用 {@link AgentStateStore} 取代 1.x 的 {@code Session}：Agent 不再自持会话状态，而是把
 * 可变状态按 {@code (userId, sessionId)} 外置到 StateStore，由框架在 {@code call} 链路上自动
 * 加载 / 持久化，天然支持单实例并发服务多租户多会话。本配置提供一个 {@link AgentStateStore} Bean，
 * 支持四种后端：</p>
 * <ul>
 *   <li><b>memory</b>：{@link InMemoryAgentStateStore}，进程内、重启丢失，适合本地联调；</li>
 *   <li><b>json</b>：{@link JsonFileAgentStateStore}，文件落盘，单机重启可恢复；</li>
 *   <li><b>redis</b>：{@link JedisAgentStateStore}（基于 Jedis），分布式共享、多实例横向扩容；</li>
 *   <li><b>mysql</b>：{@link MysqlAgentStateStore}（JDBC + HikariCP），强一致、可审计。</li>
 * </ul>
 *
 * <p>切换后端只改一行配置 {@code customer-work.session.mode}，业务代码对底层存储无感知。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    @Bean
    public AgentStateStore agentStateStore(CustomerWorkProperties properties) {
        return buildStateStore(properties.getSession());
    }

    /** 按配置构建 AgentStateStore（抽出以便单测）。 */
    AgentStateStore buildStateStore(CustomerWorkProperties.Session cfg) {
        String mode = cfg.getMode() == null ? "memory" : cfg.getMode().trim().toLowerCase();
        switch (mode) {
            case "json": {
                Path dir = Path.of(cfg.getDirectory());
                log.info("State persistence: JsonFileAgentStateStore, directory={}", dir.toAbsolutePath());
                return new JsonFileAgentStateStore(dir);
            }
            case "redis": {
                CustomerWorkProperties.Session.Redis r = cfg.getRedis();
                log.info("State persistence: JedisAgentStateStore, {}:{} keyPrefix={}",
                    r.getHost(), r.getPort(), r.getKeyPrefix());
                return JedisAgentStateStore.builder()
                    .jedisPool(buildJedisPool(r))
                    .keyPrefix(r.getKeyPrefix())
                    .build();
            }
            case "mysql": {
                CustomerWorkProperties.Session.Mysql m = cfg.getMysql();
                log.info("State persistence: MysqlAgentStateStore, {}", m.resolveJdbcUrl());
                return new MysqlAgentStateStore(buildDataSource(m), m.isAutoCreate());
            }
            default: {
                log.info("State persistence: InMemoryAgentStateStore (in-process, lost on restart)");
                return new InMemoryAgentStateStore();
            }
        }
    }

    /** 构建 Jedis 连接池（惰性连接，构造本身不连接 Redis）。 */
    JedisPool buildJedisPool(CustomerWorkProperties.Session.Redis r) {
        HostAndPort hostAndPort = new HostAndPort(r.getHost(), r.getPort());
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        if (r.getPassword() != null && !r.getPassword().isBlank()) {
            return new JedisPool(poolConfig, hostAndPort.getHost(), hostAndPort.getPort(),
                2000, r.getPassword());
        }
        return new JedisPool(poolConfig, hostAndPort.getHost(), hostAndPort.getPort());
    }

    /** 构建 MySQL DataSource（HikariCP）。无参构造，连接池在首次取连接时才建立（惰性）。 */
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
