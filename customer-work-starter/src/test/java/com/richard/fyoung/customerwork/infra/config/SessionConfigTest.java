package com.richard.fyoung.customerwork.infra.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.richard.fyoung.customerwork.infra.config.properties.SessionProperties;

/**
 * 状态持久化配置单测（离线，不连接任何外部服务）：
 * 验证按 mode 选择正确的 {@link AgentStateStore} 类型，以及 Redis/MySQL 客户端构建与 JDBC URL 拼装。
 * @author owlzhangfq@gmail.com
 */
class SessionConfigTest {

    private final SessionConfig config = new SessionConfig();

    private SessionProperties sessionCfg(String mode) {
        SessionProperties cfg = new CustomerWorkProperties().getSession();
        cfg.setMode(mode);
        return cfg;
    }

    @Test
    void buildStateStore_memory_shouldReturnInMemoryStore() {
        assertInstanceOf(InMemoryAgentStateStore.class, config.buildStateStore(sessionCfg("memory")));
    }

    @Test
    void buildStateStore_default_shouldFallbackToInMemory() {
        assertInstanceOf(InMemoryAgentStateStore.class, config.buildStateStore(sessionCfg("unknown-mode")));
    }

    /** 文件落盘形态已下线（会话状态是真实数据，不该只存在于某一台机器的磁盘上）。 */
    @Test
    void buildStateStore_json_shouldFallbackToInMemory_sinceFileModeRemoved() {
        assertInstanceOf(InMemoryAgentStateStore.class, config.buildStateStore(sessionCfg("json")));
    }

    @Test
    void buildJedisPool_shouldConstructLazilyWithoutConnecting() {
        // 仅构造连接池（惰性），不发命令，因此无需 Redis 在线
        JedisPool pool = config.buildJedisPool(new CustomerWorkProperties().getSession().getRedis());
        assertNotNull(pool);
        pool.close();
    }

    @Test
    void buildDataSource_shouldBuildHikariWithResolvedUrl() {
        SessionProperties.Mysql m = new CustomerWorkProperties().getSession().getMysql();
        DataSource ds = config.buildDataSource(m);
        assertInstanceOf(HikariDataSource.class, ds);
        HikariDataSource hikari = (HikariDataSource) ds;
        assertEquals("root", hikari.getUsername());
        assertTrue(hikari.getJdbcUrl().contains("agent_scope_customer_work"),
            "JDBC URL 应包含数据库名: " + hikari.getJdbcUrl());
        assertTrue(hikari.getJdbcUrl().contains("createDatabaseIfNotExist=true"),
            "应自动建库: " + hikari.getJdbcUrl());
        hikari.close();
    }

    @Test
    void resolveJdbcUrl_shouldHonorExplicitOverride() {
        SessionProperties.Mysql m = new CustomerWorkProperties().getSession().getMysql();
        m.setJdbcUrl("jdbc:mysql://db.internal:3307/custom_db");
        assertEquals("jdbc:mysql://db.internal:3307/custom_db", m.resolveJdbcUrl());
    }
}
