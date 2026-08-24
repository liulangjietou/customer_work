package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V97 真库回归：运行时投递表必须能与实验、Agent 等规范表直接比较字符串标识。 */
class ModelAgentReferenceCollationMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void migrationShouldMatchDbaMirror() throws Exception {
        String migration = Files.readString(Path.of(
            "src/main/resources/db/migration/V97__align_runtime_delivery_collation.sql"),
            StandardCharsets.UTF_8);
        String mirror = Files.readString(Path.of(
            "../mysql/02-customer-admin/97-V97__align_runtime_delivery_collation.sql"),
            StandardCharsets.UTF_8);

        assertEquals(migration, mirror);
    }

    @Test
    void v97ShouldAlignRuntimeDeliveryCollationsAndAllowReferenceQuery() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V97 真库迁移测试");
        String database = "admin_v97_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V97 真库迁移测试");

        PooledDataSource dataSource = null;
        try {
            migrate(database, "96");
            try (Connection connection = connection(database)) {
                seedRuntimeDelivery(connection);
                assertEquals("utf8mb4_0900_ai_ci",
                    columnCollation(connection, "ai_runtime_publish_task", "id"));
                assertEquals("utf8mb4_0900_ai_ci",
                    columnCollation(connection, "ai_runtime_publish_task", "tenant_id"));
                assertEquals("utf8mb4_unicode_ci",
                    columnCollation(connection, "ai_model_experiment", "deactivation_task_id"));
                assertEquals("utf8mb4_unicode_ci",
                    columnCollation(connection, "ai_model_experiment", "tenant_id"));

                SQLException error = assertThrows(SQLException.class,
                    () -> executeProblemJoin(connection));
                assertEquals(1267, error.getErrorCode());
            }

            migrate(database, null);
            try (Connection connection = connection(database)) {
                assertAllTextColumnsAligned(connection, "ai_runtime_publish_task");
                assertAllTextColumnsAligned(connection, "ai_runtime_config_ack");
                assertEquals(1L, rowCount(connection, "ai_runtime_publish_task"));
                assertEquals(1L, rowCount(connection, "ai_runtime_config_ack"));
                executeProblemJoin(connection);
            }

            dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl(database),
                USERNAME, PASSWORD);
            SqlSessionFactory factory = sqlSessionFactory(dataSource);
            try (SqlSession session = factory.openSession()) {
                assertTrue(session.getMapper(ModelAgentReferenceMapper.class)
                    .findReferences(-1L, null).isEmpty());
            }
        } finally {
            if (dataSource != null) {
                dataSource.forceCloseAll();
            }
            dropDatabase(database);
        }
    }

    private void seedRuntimeDelivery(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, revision, status, next_attempt_at_ms, "
                + "created_at_ms, updated_at_ms) VALUES "
                + "('task-v97', 'tenant-v97', 1, 'revision-v97', 'APPLIED', 0, 1, 1)");
            statement.executeUpdate("INSERT INTO ai_runtime_config_ack "
                + "(tenant_id, revision, instance_id, status, applied_at_ms, created_at_ms, updated_at_ms) "
                + "VALUES ('tenant-v97', 'revision-v97', 'instance-v97', 'APPLIED', 1, 1, 1)");
        }
    }

    private SqlSessionFactory sqlSessionFactory(PooledDataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("model-agent-reference-collation-test",
            new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/ModelAgentReferenceMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(resource)) {
            assertNotNull(input, "Mapper XML 不存在: " + resource);
            new XMLMapperBuilder(input, configuration, resource,
                configuration.getSqlFragments()).parse();
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void executeProblemJoin(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ai_model_experiment experiment "
            + "LEFT JOIN ai_runtime_publish_task deactivation "
            + "ON deactivation.id = experiment.deactivation_task_id "
            + "AND deactivation.tenant_id = experiment.tenant_id "
            + "AND deactivation.experiment_id = experiment.id "
            + "AND deactivation.experiment_publish_action = 'DEACTIVATE'";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            assertEquals(0L, resultSet.getLong(1));
        }
    }

    private void assertAllTextColumnsAligned(Connection connection, String table) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
            + "AND COLLATION_NAME IS NOT NULL AND COLLATION_NAME <> 'utf8mb4_unicode_ci'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(0L, resultSet.getLong(1));
            }
        }
    }

    private long rowCount(Connection connection, String table) throws Exception {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("illegal table identifier: " + table);
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String columnCollation(Connection connection, String table, String column) throws Exception {
        String sql = "SELECT COLLATION_NAME FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private void migrate(String database, String target) {
        FluentConfiguration configuration = Flyway.configure()
            .dataSource(jdbcUrl(database), USERNAME, PASSWORD)
            .locations("classpath:db/migration")
            .placeholderReplacement(false);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private boolean createDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoted(database)
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            return true;
        } catch (Exception e) {
            dropDatabase(database);
            return false;
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + quoted(database));
        } catch (Exception ignored) {
            // 清理失败不覆盖原始断言；随机库名可按 admin_v97_* 识别。
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
    }

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
    }

    private String jdbcUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1_500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String quoted(String identifier) {
        if (!identifier.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("illegal database identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
