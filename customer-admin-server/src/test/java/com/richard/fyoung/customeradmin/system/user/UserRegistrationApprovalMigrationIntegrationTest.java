package com.richard.fyoung.customeradmin.system.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V98 真库迁移：自助注册审核列、存量账号兼容及 DBA 镜像契约。 */
class UserRegistrationApprovalMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void migration_shouldBePreflightedAndMatchDbaMirrorByteForByte() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/migration/V98__admin_self_registration_approval.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String mirror = Files.readString(repositoryRoot().resolve(
            "mysql/02-customer-admin/98-V98__admin_self_registration_approval.sql"));

        assertEquals(sql, mirror, "Flyway 迁移与 DBA 镜像必须逐字一致");
        assertTrue(sql.startsWith("-- 后台本地账号自助注册审核"));
        assertTrue(sql.contains("SET NAMES utf8mb4;"));
        assertTrue(sql.contains("__customer_admin_v98_sys_user_preflight_failed__"));
        assertTrue(sql.contains("DEFAULT ''APPROVED''"));
        assertTrue(sql.contains("idx_sys_user_approval"));
    }

    @Test
    void migration_shouldApproveLegacyUsersAndAllowPendingRegistrations() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V98 真库迁移测试");
        String database = "admin_v98_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V98 真库迁移测试");

        try {
            migrate(database, "97");
            try (Connection connection = connection(database)) {
                assertFalse(columnExists(connection, "approval_status"),
                    "V97 快照不应提前包含注册审核列");
            }

            migrate(database, "98");
            try (Connection connection = connection(database);
                 Statement statement = connection.createStatement()) {
                assertEquals("APPROVED", queryString(connection,
                    "SELECT approval_status FROM sys_user WHERE username = 'admin'"),
                    "存量管理员必须保持已批准，升级后不能丢权限");
                assertEquals("NO", queryString(connection,
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sys_user' AND column_name = 'approval_status'"));
                assertEquals("APPROVED", queryString(connection,
                    "SELECT column_default FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sys_user' AND column_name = 'approval_status'"));

                statement.executeUpdate("INSERT INTO sys_user (username, password, nickname) "
                    + "VALUES ('v98-admin-created', 'hash', 'Admin Created')");
                statement.executeUpdate("INSERT INTO sys_user "
                    + "(username, password, nickname, approval_status) "
                    + "VALUES ('v98-self-register', 'hash', 'Self Register', 'PENDING')");

                assertEquals("APPROVED", queryString(connection,
                    "SELECT approval_status FROM sys_user WHERE username = 'v98-admin-created'"));
                assertEquals("PENDING", queryString(connection,
                    "SELECT approval_status FROM sys_user WHERE username = 'v98-self-register'"));
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sys_user' AND index_name = 'idx_sys_user_approval'"));
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '98' AND success = 1"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    private void migrate(String database, String target) {
        Flyway.configure()
            .dataSource(databaseUrl(database), USERNAME, PASSWORD)
            .locations("classpath:db/migration")
            .placeholderReplacement(false)
            .target(target)
            .load()
            .migrate();
    }

    private boolean createDatabase(String database) {
        try (Connection connection = DriverManager.getConnection(serverUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = DriverManager.getConnection(serverUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        } catch (Exception ignored) {
            // 测试清理失败不覆盖主断言；数据库名带随机后缀，不会碰共享库。
        }
    }

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(databaseUrl(database), USERNAME, PASSWORD);
    }

    private boolean columnExists(Connection connection, String column) throws Exception {
        return queryInt(connection,
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'sys_user' AND column_name = '" + column + "'") == 1;
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int queryInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT
            + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private String databaseUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("mysql/02-customer-admin")) ? cwd : cwd.getParent();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
