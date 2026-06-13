package com.example.customerwork.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话持久化配置单测（离线，不连接任何外部服务）：
 * 验证按 mode 选择正确的 Session 类型，以及 Redis/MySQL 客户端构建与 JDBC URL 拼装。
 */
class SessionConfigTest {

    private final SessionConfig config = new SessionConfig();

    private CustomerWorkProperties.Session sessionCfg(String mode) {
        CustomerWorkProperties.Session cfg = new CustomerWorkProperties().getSession();
        cfg.setMode(mode);
        return cfg;
    }

    @Test
    void buildSession_memory_shouldReturnInMemorySession() {
        assertInstanceOf(InMemorySession.class, config.buildSession(sessionCfg("memory")));
    }

    @Test
    void buildSession_default_shouldFallbackToInMemory() {
        assertInstanceOf(InMemorySession.class, config.buildSession(sessionCfg("unknown-mode")));
    }

    @Test
    void buildSession_json_shouldReturnJsonSession() {
        CustomerWorkProperties.Session cfg = sessionCfg("json");
        cfg.setDirectory("target/test-sessions");
        Session session = config.buildSession(cfg);
        assertInstanceOf(JsonSession.class, session);
    }

    @Test
    void buildJedis_shouldConstructLazilyWithoutConnecting() {
        // 仅构造客户端（惰性），不发命令，因此无需 Redis 在线
        UnifiedJedis jedis = config.buildJedis(new CustomerWorkProperties().getSession().getRedis());
        assertNotNull(jedis);
        jedis.close();
    }

    @Test
    void buildDataSource_shouldBuildHikariWithResolvedUrl() {
        CustomerWorkProperties.Session.Mysql m = new CustomerWorkProperties().getSession().getMysql();
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
        CustomerWorkProperties.Session.Mysql m = new CustomerWorkProperties().getSession().getMysql();
        m.setJdbcUrl("jdbc:mysql://db.internal:3307/custom_db");
        assertEquals("jdbc:mysql://db.internal:3307/custom_db", m.resolveJdbcUrl());
    }
}
