package com.richard.fyoung.customeradmin.aiconfig.model;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V78 真库测试：迁移后同秒 probe 可以按微秒稳定排序。 */
class ModelHealthProbePrecisionMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void v78_shouldPreserveMicrosecondsInSnapshotAndEvent() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V78 真库迁移测试");
        String database = "admin_v78_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V78 真库迁移测试");
        try {
            try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE ai_model_health_snapshot ("
                    + "model_config_id BIGINT PRIMARY KEY, last_probe_at DATETIME NULL)");
                statement.execute("CREATE TABLE ai_model_health_event ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, occurred_at DATETIME NOT NULL)");
                String migration = Files.readString(Path.of(
                    "src/main/resources/db/migration/V78__model_health_probe_precision.sql"));
                for (String sql : migration.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }

            LocalDateTime probeAt = LocalDateTime.of(2026, 8, 22, 12, 30, 15, 123456000);
            try (Connection connection = connection(database)) {
                try (PreparedStatement insertSnapshot = connection.prepareStatement(
                    "INSERT INTO ai_model_health_snapshot (model_config_id, last_probe_at) VALUES (1, ?)")) {
                    insertSnapshot.setObject(1, probeAt);
                    insertSnapshot.executeUpdate();
                }
                try (PreparedStatement insertEvent = connection.prepareStatement(
                    "INSERT INTO ai_model_health_event (occurred_at) VALUES (?)")) {
                    insertEvent.setObject(1, probeAt);
                    insertEvent.executeUpdate();
                }
                assertEquals(6, datetimePrecision(connection, "ai_model_health_snapshot", "last_probe_at"));
                assertEquals(6, datetimePrecision(connection, "ai_model_health_event", "occurred_at"));
                assertEquals(probeAt, timestamp(connection,
                    "SELECT last_probe_at FROM ai_model_health_snapshot WHERE model_config_id = 1"));
                assertEquals(probeAt, timestamp(connection,
                    "SELECT occurred_at FROM ai_model_health_event WHERE id = 1"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    private int datetimePrecision(Connection connection, String table, String column) throws Exception {
        String sql = "SELECT DATETIME_PRECISION FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private LocalDateTime timestamp(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getObject(1, LocalDateTime.class);
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
            // 清理失败不覆盖原始断言；随机库名可按 admin_v78_* 精确识别。
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
