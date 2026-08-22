package com.richard.fyoung.customeradmin.slo;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V79 真库测试：存量租户策略回填保守门槛，并由数据库拒绝非法值。 */
class SloMinimumSampleMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void v79_shouldBackfillEveryExistingTenantPolicyAndRejectNonPositiveValue() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V79 真库迁移测试");
        String database = "admin_v79_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V79 真库迁移测试");
        try {
            migrateExistingPolicies(database);
            try (Connection connection = connection(database)) {
                assertEquals(100, minimumSampleCount(connection, "tenant-a"));
                assertEquals(100, minimumSampleCount(connection, "tenant-b"));
                assertThrows(SQLException.class, () -> updateMinimumSampleCount(connection, "tenant-a", 0));
            }
        } finally {
            dropDatabase(database);
        }
    }

    private void migrateExistingPolicies(String database) throws Exception {
        try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ai_slo_policy ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
                + "long_window_minutes INT NOT NULL)");
            statement.executeUpdate("INSERT INTO ai_slo_policy (tenant_id, long_window_minutes) "
                + "VALUES ('tenant-a', 60), ('tenant-b', 60)");
            String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V79__slo_minimum_sample_count.sql"));
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private int minimumSampleCount(Connection connection, String tenantId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT minimum_sample_count FROM ai_slo_policy WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void updateMinimumSampleCount(Connection connection, String tenantId, int count)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE ai_slo_policy SET minimum_sample_count = ? WHERE tenant_id = ?")) {
            statement.setInt(1, count);
            statement.setString(2, tenantId);
            statement.executeUpdate();
        }
    }

    private boolean createDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoted(database)
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
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
            // 清理失败不覆盖原始断言；随机库名可按 admin_v79_* 精确识别。
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
