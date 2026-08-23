package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 MySQL/Mapper 验证认证晋级的配置 CAS 与旧慢运行拒绝。 */
class ModelCertificationMapperIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void promotion_shouldRequireCurrentEndpointAndSecretAndRejectOlderAttemptId() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过模型认证 Mapper 真库测试");
        String database = "model_cert_mapper_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过模型认证 Mapper 真库测试");
        PooledDataSource dataSource = null;
        try {
            createSchema(database);
            dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl(database),
                USERNAME, PASSWORD);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("model-certification-test",
                new JdbcTransactionFactory(), dataSource));
            parseMapper(configuration);
            SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);

            try (SqlSession session = factory.openSession(true)) {
                AiModelCertificationMapper mapper = session.getMapper(AiModelCertificationMapper.class);
                mapper.promoteIfCurrent(certification(100L, 3, 4), 20L);
                assertEquals(100L, currentRun(session));

                try (Statement statement = session.getConnection().createStatement()) {
                    statement.executeUpdate("UPDATE ai_model_config SET endpoint_revision = 4 WHERE id = 7");
                }
                mapper.promoteIfCurrent(certification(200L, 3, 4), 20L);
                assertEquals(100L, currentRun(session), "端点已变化的运行不得晋级");

                try (Statement statement = session.getConnection().createStatement()) {
                    statement.executeUpdate("UPDATE ai_model_config SET endpoint_revision = 3 WHERE id = 7");
                }
                mapper.promoteIfCurrent(certification(200L, 3, 4), 20L);
                assertEquals(200L, currentRun(session));
                mapper.promoteIfCurrent(certification(150L, 3, 4), 20L);
                assertEquals(200L, currentRun(session), "更早启动但更晚完成的运行不得覆盖 current");

                try (Statement statement = session.getConnection().createStatement()) {
                    statement.executeUpdate("UPDATE ai_secret_ref SET current_version = 5 WHERE id = 20");
                }
                mapper.promoteIfCurrent(certification(300L, 3, 4), 20L);
                assertEquals(200L, currentRun(session), "旧凭据版本不得晋级");
            }
        } finally {
            if (dataSource != null) {
                dataSource.forceCloseAll();
            }
            dropDatabase(database);
        }
    }

    private AiModelCertification certification(Long runId, int endpointRevision, int secretVersion) {
        AiModelCertification certification = new AiModelCertification();
        certification.setModelConfigId(7L);
        certification.setTenantId("tenant-a");
        certification.setStatus("PASSED");
        certification.setCurrentRunId(runId);
        certification.setCertifiedEndpointRevision(endpointRevision);
        certification.setCertifiedSecretVersion(secretVersion);
        certification.setValidUntil(LocalDateTime.now().plusDays(1));
        certification.setCompletedAt(LocalDateTime.now());
        certification.setPassedChecks(3);
        certification.setFailedChecks(0);
        certification.setRevision(1);
        return certification;
    }

    private long currentRun(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "SELECT current_run_id FROM ai_model_certification WHERE model_config_id = 7")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void parseMapper(MybatisConfiguration configuration) throws Exception {
        String resource = "mapper/AiModelCertificationMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(resource)) {
            assertNotNull(input, "Mapper XML 不存在: " + resource);
            new XMLMapperBuilder(input, configuration, resource,
                configuration.getSqlFragments()).parse();
        }
    }

    private void createSchema(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ai_model_config (id BIGINT PRIMARY KEY, "
                + "tenant_id VARCHAR(64) NOT NULL, endpoint_revision INT NOT NULL, "
                + "secret_ref_id BIGINT, deleted TINYINT NOT NULL)");
            statement.execute("CREATE TABLE ai_secret_ref (id BIGINT PRIMARY KEY, "
                + "tenant_id VARCHAR(64) NOT NULL, current_version INT NOT NULL, "
                + "status VARCHAR(16) NOT NULL, deleted TINYINT NOT NULL)");
            statement.execute("CREATE TABLE ai_model_certification ("
                + "model_config_id BIGINT PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
                + "status VARCHAR(16) NOT NULL, current_run_id BIGINT, "
                + "certified_endpoint_revision INT, certified_secret_version INT, "
                + "valid_until DATETIME, completed_at DATETIME, passed_checks INT NOT NULL, "
                + "failed_checks INT NOT NULL, latency_p95_ms BIGINT, verified_context_tokens INT, "
                + "failure_code VARCHAR(64), failure_message VARCHAR(500), revision INT NOT NULL)");
            statement.execute("INSERT INTO ai_model_config VALUES (7, 'tenant-a', 3, 20, 0)");
            statement.execute("INSERT INTO ai_secret_ref VALUES (20, 'tenant-a', 4, 'ACTIVE', 0)");
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
        if (!database.matches("model_cert_mapper_[a-z0-9]+")) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        } catch (Exception ignored) {
            // 清理失败不覆盖原始断言；随机库名可按 model_cert_mapper_* 精确识别。
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
