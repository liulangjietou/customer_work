package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 MySQL 验证健康快照扩展字段与行锁读取映射。 */
class ModelHealthSnapshotMapperIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void insertAndLock_shouldRoundTripRoutingOverlayState() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过模型健康 Mapper 真库测试");
        String database = "model_health_mapper_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过模型健康 Mapper 真库测试");
        PooledDataSource dataSource = null;
        try {
            createSchema(database);
            dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl(database),
                USERNAME, PASSWORD);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("model-health-test",
                new JdbcTransactionFactory(), dataSource));
            parseMapper(configuration);
            SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
            LocalDateTime now = LocalDateTime.of(2026, 8, 24, 12, 0, 0, 123000000);

            try (SqlSession session = factory.openSession(false)) {
                AiModelHealthSnapshotMapper mapper = session.getMapper(AiModelHealthSnapshotMapper.class);
                assertEquals(1, mapper.insertIgnore(snapshot(now)));
                assertEquals(0, mapper.insertIgnore(snapshot(now)));
                AiModelHealthSnapshot locked = mapper.lockSnapshot(11L, "tenant-a");
                assertNotNull(locked);
                assertEquals(2, locked.getConsecutiveSuccesses());
                assertEquals("FORCE_UNHEALTHY", locked.getOverrideMode());
                assertEquals(now.plusMinutes(30), locked.getOverrideUntil());
                assertEquals(now.plusMinutes(1), locked.getCooldownUntil());
                session.rollback();
            }
        } finally {
            if (dataSource != null) {
                dataSource.forceCloseAll();
            }
            dropDatabase(database);
        }
    }

    private void parseMapper(MybatisConfiguration configuration) throws Exception {
        String resource = "mapper/AiModelHealthSnapshotMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(resource)) {
            assertNotNull(input, "Mapper XML 不存在: " + resource);
            new XMLMapperBuilder(input, configuration, resource,
                configuration.getSqlFragments()).parse();
        }
    }

    private AiModelHealthSnapshot snapshot(LocalDateTime now) {
        AiModelHealthSnapshot snapshot = new AiModelHealthSnapshot();
        snapshot.setModelConfigId(11L);
        snapshot.setTenantId("tenant-a");
        snapshot.setHealthStatus("HEALTHY");
        snapshot.setAuthStatus("PASSED");
        snapshot.setCapabilityStatus("UNKNOWN");
        snapshot.setConsecutiveFailures(0);
        snapshot.setConsecutiveSuccesses(2);
        snapshot.setLastLatencyMs(10L);
        snapshot.setLastProbeAt(now);
        snapshot.setLastSuccessAt(now);
        snapshot.setNextProbeAt(now.plusMinutes(5));
        snapshot.setCooldownUntil(now.plusMinutes(1));
        snapshot.setOverrideMode("FORCE_UNHEALTHY");
        snapshot.setOverrideReason("operator isolation");
        snapshot.setOverrideOperatorId(7L);
        snapshot.setOverrideOperatorName("operator");
        snapshot.setOverrideUntil(now.plusMinutes(30));
        snapshot.setRevision(3);
        return snapshot;
    }

    private void createSchema(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ai_model_health_snapshot ("
                + "model_config_id BIGINT PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
                + "health_status VARCHAR(16) NOT NULL, auth_status VARCHAR(16) NOT NULL, "
                + "capability_status VARCHAR(16) NOT NULL, consecutive_failures INT NOT NULL, "
                + "consecutive_successes INT NOT NULL, last_latency_ms BIGINT, "
                + "last_error_category VARCHAR(32), last_message VARCHAR(500), "
                + "last_probe_at DATETIME(6), last_success_at DATETIME(6), last_failure_at DATETIME(6), "
                + "next_probe_at DATETIME(6), cooldown_until DATETIME(6), "
                + "override_mode VARCHAR(24) NOT NULL, override_reason VARCHAR(500), "
                + "override_operator_id BIGINT, override_operator_name VARCHAR(100), "
                + "override_until DATETIME(6), revision INT NOT NULL, "
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        }
    }

    private boolean createDatabase(String database) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
            return true;
        } catch (Exception e) {
            dropDatabase(database);
            return false;
        }
    }

    private void dropDatabase(String database) {
        if (!database.matches("model_health_mapper_[a-z0-9]+")) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        } catch (Exception ignored) {
            // 清理失败不覆盖原始断言；随机库名可按 model_health_mapper_* 精确识别。
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String jdbcUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
