package com.richard.fyoung.customeradmin.tenant;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V99 真库迁移：角色编码租户内唯一，并修复存量租户缺少内建角色的问题。 */
class TenantRoleScopeMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void migration_shouldBePreflightedAndMatchDbaMirrorByteForByte() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/migration/V99__tenant_scoped_roles_and_approval_binding.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String mirror = Files.readString(repositoryRoot().resolve(
            "mysql/02-customer-admin/99-V99__tenant_scoped_roles_and_approval_binding.sql"));

        assertEquals(sql, mirror, "Flyway 迁移与 DBA 镜像必须逐字一致");
        assertTrue(sql.startsWith("-- 审核绑定租户前先修正角色领域约束"));
        assertTrue(sql.contains("__customer_admin_v99_sys_role_preflight_failed__"));
        assertTrue(sql.contains("uk_sys_role_tenant_code"));
        assertTrue(sql.contains("'tenant_admin'"));
    }

    @Test
    void migration_shouldScopeRoleCodesAndProvisionExistingActiveTenants() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V99 真库迁移测试");
        String database = "admin_v99_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V99 真库迁移测试");

        try {
            migrate(database, "98");
            try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO sys_tenant "
                    + "(tenant_code, tenant_name, status) VALUES ('tenant-a', 'Tenant A', 'ACTIVE')");
                assertEquals("role_code", indexColumns(connection, "uk_sys_role_code"));
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM sys_role WHERE tenant_id = 'tenant-a'"));
            }

            migrate(database, "99");
            try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
                assertEquals("tenant_id,role_code", indexColumns(connection, "uk_sys_role_tenant_code"));
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sys_role' AND index_name = 'uk_sys_role_code'"));
                assertEquals(2, queryInt(connection,
                    "SELECT COUNT(*) FROM sys_role WHERE role_code = 'tenant_admin' AND deleted = 0"));
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM sys_role WHERE tenant_id = 'tenant-a' "
                        + "AND role_code = 'tenant_admin' AND data_scope = 'TENANT' AND control_plane = 0"));
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM sys_role_permission rp "
                        + "JOIN sys_role r ON r.id = rp.role_id "
                        + "JOIN sys_permission p ON p.id = rp.permission_id "
                        + "WHERE r.tenant_id = 'tenant-a' AND r.role_code = 'tenant_admin' "
                        + "AND p.perm_code = 'user:view'"));
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM sys_role_permission rp "
                        + "JOIN sys_role r ON r.id = rp.role_id "
                        + "JOIN sys_permission p ON p.id = rp.permission_id "
                        + "WHERE r.tenant_id = 'tenant-a' AND r.role_code = 'tenant_admin' "
                        + "AND (p.perm_code = 'tenant' OR p.perm_code LIKE 'tenant:%')"));

                statement.executeUpdate("INSERT INTO sys_role "
                    + "(role_name, role_code, tenant_id) VALUES ('Default Auditor', 'auditor', 'default')");
                statement.executeUpdate("INSERT INTO sys_role "
                    + "(role_name, role_code, tenant_id) VALUES ('Tenant Auditor', 'auditor', 'tenant-a')");
                assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO sys_role "
                    + "(role_name, role_code, tenant_id) VALUES ('Duplicate', 'auditor', 'tenant-a')"));
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '99' AND success = 1"));
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

    private String indexColumns(Connection connection, String indexName) throws Exception {
        return queryString(connection,
            "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') "
                + "FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'sys_role' AND index_name = '" + indexName + "'");
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
