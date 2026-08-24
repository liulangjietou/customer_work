package com.richard.fyoung.customeradmin.businessoutcome.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeAggregateRow;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionRow;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 MySQL 下验证历史表排序规则不同仍能聚合业务结果。 */
class BusinessOutcomeMapperCollationIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";

    @Test
    void mixedSessionIdCollations_shouldAggregateAndDrillDown() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过业务结果排序规则集成测试");
        String database = "business_outcome_collation_"
            + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        PooledDataSource dataSource = null;
        try {
            createDatabase(database);
            created = true;
            createSchemaAndFacts(database);
            dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl(database),
                USERNAME, PASSWORD);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setEnvironment(new Environment("collation-test",
                new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(BusinessOutcomeMapper.class);
            MybatisPlusInterceptor tenantInterceptor = new MybatisPlusInterceptor();
            tenantInterceptor.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of()));
            configuration.addInterceptor(tenantInterceptor);
            SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);

            TenantContext.set("tenant-a");
            try (SqlSession session = factory.openSession()) {
                BusinessOutcomeMapper mapper = session.getMapper(BusinessOutcomeMapper.class);
                assertEquals(2L, mapper.countSessions("tenant-a", null, 0L, 1_000L));
                BusinessOutcomeAggregateRow aggregate = mapper.aggregate("tenant-a", null, 0L, 1_000L);
                assertEquals(2L, aggregate.getTotalSessions());
                assertEquals(1L, aggregate.getHandoffSessions());
                assertEquals(1L, aggregate.getAutoResolvedProxySessions());
                assertEquals(1L, aggregate.getCsatInvitedSessions());
                assertEquals(1L, aggregate.getCsatRespondedSessions());
                assertEquals(1L, aggregate.getCsatSatisfiedSessions());
                assertEquals(5, aggregate.getAverageCsat().intValue());

                List<BusinessOutcomeSessionRow> sessions = mapper.findSessions(
                    "tenant-a", null, 0L, 1_000L, 0L, 20);
                assertEquals(2, sessions.size());
                BusinessOutcomeSessionRow positive = session(sessions, "session-1");
                assertTrue(positive.getHandedOff());
                assertEquals(5, positive.getCsatScore());
                BusinessOutcomeSessionRow tenantCollision = session(sessions, "tenant-collision");
                assertFalse(tenantCollision.getHandedOff(),
                    "tenant-b 的同 sessionId 转人工事实不得污染 tenant-a");
                assertNull(tenantCollision.getCsatScore(),
                    "tenant-b 的同 sessionId 满意度不得污染 tenant-a");
            }
        } finally {
            TenantContext.clear();
            if (dataSource != null) {
                dataSource.forceCloseAll();
            }
            if (created) {
                dropDatabase(database);
            }
        }
    }

    private void createDatabase(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private void createSchemaAndFacts(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE cw_agent_call_log (
                    tenant_id VARCHAR(64) COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    session_id VARCHAR(128) COLLATE utf8mb4_0900_ai_ci,
                    agent_code VARCHAR(64) COLLATE utf8mb4_0900_ai_ci,
                    start_time BIGINT NOT NULL,
                    success TINYINT NOT NULL,
                    total_tokens BIGINT
                ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                """);
            statement.execute("""
                CREATE TABLE cw_ticket (
                    tenant_id VARCHAR(64) COLLATE utf8mb4_unicode_ci NOT NULL,
                    session_id VARCHAR(128) COLLATE utf8mb4_unicode_ci,
                    handoff_at_ms BIGINT,
                    handoff_reason VARCHAR(255)
                ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                """);
            statement.execute("""
                CREATE TABLE cw_csat_survey (
                    tenant_id VARCHAR(64) COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    session_id VARCHAR(128) COLLATE utf8mb4_0900_ai_ci,
                    score INT
                ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                """);
            statement.execute("INSERT INTO cw_agent_call_log VALUES "
                + "('tenant-a', 'session-1', 'support-agent', 100, 1, 42), "
                + "('tenant-a', 'tenant-collision', 'support-agent', 200, 1, 21)");
            statement.execute("INSERT INTO cw_ticket VALUES "
                + "('tenant-a', 'session-1', 100, 'need human'), "
                + "('tenant-b', 'tenant-collision', 200, 'other tenant')");
            statement.execute("INSERT INTO cw_csat_survey VALUES "
                + "('tenant-a', 'session-1', 5), "
                + "('tenant-b', 'tenant-collision', 1)");
        }
    }

    private BusinessOutcomeSessionRow session(List<BusinessOutcomeSessionRow> sessions, String sessionId) {
        return sessions.stream()
            .filter(row -> sessionId.equals(row.getSessionId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing session: " + sessionId));
    }

    private void dropDatabase(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String jdbcUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
