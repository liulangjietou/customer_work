package com.richard.fyoung.customeradmin.workspace.session;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V58 真库迁移测试：排序规则一致后，工作区归属表才能与框架状态表按 session_id 连接。 */
class WorkspaceSessionCollationMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void v58ShouldAlignCollationsAndAllowOwnershipJoin() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V58 真库迁移测试");
        String database = "admin_v58_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V58 真库迁移测试");

        try {
            migrate(database, "57");
            try (Connection connection = connection(database)) {
                assertEquals("utf8mb4_unicode_ci", columnCollation(
                    connection, "ai_chat_session_state", "session_id"));
                assertEquals("utf8mb4_0900_ai_ci", columnCollation(
                    connection, "ai_workspace_session", "session_id"));
            }

            migrate(database, null);
            try (Connection connection = connection(database)) {
                assertWorkspaceSessionCollations(connection);
                assertOwnershipJoin(connection);
            }
        } finally {
            dropDatabase(database);
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

    private void assertWorkspaceSessionCollations(Connection connection) throws Exception {
        String sql = "SELECT COLUMN_NAME, COLLATION_NAME FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_workspace_session' "
            + "AND COLUMN_NAME IN ('tenant_id', 'agent_code', 'session_id')";
        Map<String, String> collations = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                collations.put(resultSet.getString(1), resultSet.getString(2));
            }
        }
        assertEquals(Map.of(
            "tenant_id", "utf8mb4_unicode_ci",
            "agent_code", "utf8mb4_unicode_ci",
            "session_id", "utf8mb4_unicode_ci"), collations);
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

    private void assertOwnershipJoin(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String agentCode = "collation-" + suffix;
        String sessionId = "session-" + suffix;
        long ownerUserId = 987654321L;
        try {
            insertWorkspaceSession(connection, agentCode, sessionId, ownerUserId);
            insertChatState(connection, agentCode + ":" + sessionId);

            String sql = "SELECT COUNT(DISTINCT state.session_id) "
                + "FROM ai_chat_session_state state "
                + "INNER JOIN ai_workspace_session owner "
                + "ON owner.tenant_id = ? AND owner.agent_code = ? "
                + "AND owner.session_id = SUBSTRING(state.session_id, CHAR_LENGTH(?) + 2) "
                + "AND owner.owner_user_id = ? "
                + "WHERE state.session_id LIKE CONCAT(?, ':%')";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, "default");
                statement.setString(2, agentCode);
                statement.setString(3, agentCode);
                statement.setLong(4, ownerUserId);
                statement.setString(5, agentCode);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    assertEquals(1L, resultSet.getLong(1));
                }
            }
        } finally {
            connection.rollback();
        }
    }

    private void insertWorkspaceSession(Connection connection, String agentCode,
                                        String sessionId, long ownerUserId) throws Exception {
        String sql = "INSERT INTO ai_workspace_session "
            + "(tenant_id, agent_code, session_id, owner_user_id, created_at_ms, updated_at_ms) "
            + "VALUES ('default', ?, ?, ?, 1, 1)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentCode);
            statement.setString(2, sessionId);
            statement.setLong(3, ownerUserId);
            statement.executeUpdate();
        }
    }

    private void insertChatState(Connection connection, String stateSessionId) throws Exception {
        String sql = "INSERT INTO ai_chat_session_state "
            + "(session_id, state_key, item_index, state_data) VALUES (?, 'context', 0, '{}')";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stateSessionId);
            statement.executeUpdate();
        }
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
            // 清理失败不覆盖原始断言；随机库名不会影响业务库，残留可按 admin_v58_* 识别。
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
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
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
