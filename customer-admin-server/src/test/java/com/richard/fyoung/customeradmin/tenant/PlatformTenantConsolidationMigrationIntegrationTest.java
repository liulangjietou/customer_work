package com.richard.fyoung.customeradmin.tenant;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V63 真库迁移测试：平台租户数据归一到 default，并用角色列显式表达控制面能力。 */
class PlatformTenantConsolidationMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    private static final Set<String> TENANT_TABLES = Set.of(
        "ai_agent", "ai_agent_backup_model", "ai_agent_knowledge_base", "ai_agent_mcp",
        "ai_agent_memory", "ai_agent_skill", "ai_agent_sub_agent", "ai_agent_system_tool",
        "ai_agent_task", "ai_channel_binding", "ai_channel_robot", "ai_channel_session",
        "ai_chat_attachment", "ai_code_knowledge_chunk", "ai_code_knowledge_index",
        "ai_code_review_task", "ai_coding_audit_log", "ai_knowledge_base", "ai_mcp",
        "ai_model_config", "ai_project", "ai_project_session", "ai_scheduled_task",
        "ai_scheduled_task_run", "ai_site_message", "ai_skill", "ai_skill_file",
        "cw_agent_call_log", "cw_agent_call_segment", "sys_operation_log", "sys_role",
        "sys_role_permission", "sys_user", "sys_user_role", "sql_datasource", "sql_define",
        "sql_define_param", "sql_field_transform", "workbench_site", "workbench_token",
        "ai_config_version", "cw_tenant_usage_daily", "ai_workspace_session",
        "ai_runtime_publish_task", "ai_runtime_config_ack");
    private static final Set<String> CONFLICT_TABLES = Set.of(
        "ai_chat_session_state", "cw_tenant_usage_daily", "ai_workspace_session",
        "ai_runtime_publish_task", "ai_runtime_config_ack");

    @Test
    void v63ShouldCoverEveryTenantTableAndMatchDbaMirror() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/migration/V63__consolidate_platform_tenant_to_default.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Pattern statementPattern = Pattern.compile("(?ms)^UPDATE\\s+`([^`]+)`.*?;");
        Matcher matcher = statementPattern.matcher(sql);
        Set<String> updatedTenantTables = new HashSet<>();
        while (matcher.find()) {
            if (matcher.group().contains("WHERE `tenant_id` = '__platform__'")) {
                updatedTenantTables.add(matcher.group(1));
            }
        }

        assertEquals(TENANT_TABLES, updatedTenantTables, "V63 必须显式覆盖全部 45 张租户表");
        assertTrue(sql.contains("ADD COLUMN `control_plane` TINYINT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("WHERE `tenant_id` = 'default'\n"
            + "  AND `role_code` IN ('super_admin', 'operator')"));
        assertTrue(sql.contains("'sensitive-word:add', 'sensitive-word:edit', 'sensitive-word:delete'"),
            "V63 必须清理普通角色的全局敏感词写权限");
        assertTrue(sql.contains("ADD UNIQUE KEY `uk_config_version_tenant` "
            + "(`tenant_id`, `config_type`, `target_code`, `version`)"));
        assertEquals(CONFLICT_TABLES, extractConflictTables(sql), "V63 必须前置预检全部已审计唯一键");
        assertTrue(sql.indexOf("DROP TEMPORARY TABLE `_v63_platform_tenant_conflict_guard`")
            < sql.indexOf("UPDATE `ai_chat_session_state`"), "冲突预检必须早于全部业务写入");
        assertFalse(sql.matches("(?ims).*^\\s*UPDATE\\s+IGNORE.*"),
            "唯一冲突不得被 UPDATE IGNORE 吞掉");
        assertFalse(sql.matches("(?ims).*^\\s*REPLACE\\s+INTO.*"),
            "唯一冲突不得被 REPLACE 吞掉");

        Path workingDirectory = Path.of("").toAbsolutePath();
        Path repositoryRoot = Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
        String mirrorSql = Files.readString(repositoryRoot.resolve(
            "mysql/02-customer-admin/63-V63__consolidate_platform_tenant_to_default.sql"));
        assertEquals(sql, mirrorSql, "Flyway 迁移与 DBA 镜像必须逐字一致");
    }

    private Set<String> extractConflictTables(String sql) {
        Pattern pattern = Pattern.compile("(?m)^\\s*INNER JOIN\\s+`([^`]+)`\\s+d$");
        Matcher matcher = pattern.matcher(sql);
        Set<String> tables = new HashSet<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

    @Test
    void v63ShouldNormalizePlatformRowsAndMarkControlPlaneRoles() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V63 真库迁移测试");
        String database = "admin_v63_" + randomSuffix();
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V63 真库迁移测试");

        try {
            migrate(database, "62");
            try (Connection connection = connection(database)) {
                assertEquals(TENANT_TABLES, tenantTables(connection), "V62 后应恰有 45 张租户表");
                seedPlatformData(connection);
            }

            migrate(database, null);

            try (Connection connection = connection(database)) {
                assertTrue(columnExists(connection, "sys_role", "control_plane"));
                assertEquals(0, countPlatformRows(connection), "45 张租户表不得残留平台租户行");
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_tenant` WHERE `tenant_code` = 'default'"));
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_tenant` WHERE `tenant_code` = '__platform__'"));
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_tenant` WHERE `tenant_code` = 'tenant-a'"));

                assertEquals(2, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_role` WHERE `role_code` IN "
                        + "('super_admin', 'operator') AND `control_plane` = 1"));
                assertEquals(0, queryInt(connection,
                    "SELECT `control_plane` FROM `sys_role` WHERE `role_code` = 'auditor'"));
                assertEquals("default", queryString(connection,
                    "SELECT `tenant_id` FROM `sys_role` WHERE `role_code` = 'auditor'"));
                assertEquals("tenant-a", queryString(connection,
                    "SELECT `tenant_id` FROM `sys_role` WHERE `role_code` = 'tenant-a-role'"));
                assertEquals("tenant_id,config_type,target_code,version", queryString(connection,
                    "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                        + "FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'ai_config_version' "
                        + "AND index_name = 'uk_config_version_tenant'"));
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_role_permission` rp "
                        + "JOIN `sys_role` r ON r.id = rp.role_id "
                        + "JOIN `sys_permission` p ON p.id = rp.permission_id "
                        + "WHERE r.role_code = 'auditor' AND p.perm_code = 'menu:edit'"),
                    "普通角色的控制面权限必须被清理");
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_role_permission` rp "
                        + "JOIN `sys_role` r ON r.id = rp.role_id "
                        + "JOIN `sys_permission` p ON p.id = rp.permission_id "
                        + "WHERE r.role_code = 'auditor' AND p.perm_code = 'agent:edit'"),
                    "租户自助权限不能被控制面清理误删");
                // billing 族在 V101 里整族收归控制面（计费看到的是全平台数据），
                // 存量授权必须被那条迁移回收——此前它是本用例的"不该误删"样本，
                // 现在恰恰是"应当回收"的样本。
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_role_permission` rp "
                        + "JOIN `sys_role` r ON r.id = rp.role_id "
                        + "JOIN `sys_permission` p ON p.id = rp.permission_id "
                        + "WHERE r.role_code = 'auditor' AND p.perm_code = 'billing:view'"),
                    "V101 应回收非控制面角色上的 billing 族授权");
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_role_permission` rp "
                        + "JOIN `sys_role` r ON r.id = rp.role_id "
                        + "JOIN `sys_permission` p ON p.id = rp.permission_id "
                        + "WHERE r.role_code = 'operator' AND p.perm_code = 'menu:edit' "
                        + "AND rp.tenant_id = 'default'"),
                    "控制面角色原有权限必须保留并归一到 default");
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM `sys_role_permission` rp "
                        + "JOIN `sys_role` r ON r.id = rp.role_id "
                        + "JOIN `sys_permission` p ON p.id = rp.permission_id "
                        + "WHERE r.role_code = 'tenant-a-role' AND p.perm_code = 'sensitive-word:edit'"),
                    "业务租户的普通角色也必须清理控制面写权限");

                assertEquals("default::assistant:session-1", queryString(connection,
                    "SELECT `session_id` FROM `ai_chat_session_state` "
                        + "WHERE `state_key` = 'platform-context'"));
                assertEquals("default::assistant", queryString(connection,
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(`state_data`, '$.user_id')) "
                        + "FROM `ai_chat_session_state` WHERE `state_key` = 'platform-context'"));
                assertEquals("kept", queryString(connection,
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(`state_data`, '$.marker')) "
                        + "FROM `ai_chat_session_state` WHERE `state_key` = 'platform-context'"));
                assertEquals("xxplatformyy::assistant:session-2", queryString(connection,
                    "SELECT `session_id` FROM `ai_chat_session_state` "
                        + "WHERE `state_key` = 'near-prefix-context'"),
                    "下划线必须按字面量匹配，不能按 LIKE 单字符通配符误改近似前缀");
                assertEquals("default::assistant", queryString(connection,
                    "SELECT `user_id` FROM `cw_agent_call_log` "
                        + "WHERE `request_id` = 'platform-request'"));
                assertEquals("xxplatformyy::assistant", queryString(connection,
                    "SELECT `user_id` FROM `cw_agent_call_log` "
                        + "WHERE `request_id` = 'near-prefix-request'"));
                assertEquals("default", queryString(connection,
                    "SELECT `tenant_id` FROM `ai_workspace_session` "
                        + "WHERE `agent_code` = 'platform-agent'"));
                assertEquals("tenant-a", queryString(connection,
                    "SELECT `tenant_id` FROM `ai_workspace_session` "
                        + "WHERE `agent_code` = 'tenant-agent'"));
                assertEquals("customer-work-runtime-config-tenant-default", queryString(connection,
                    "SELECT `data_id` FROM `ai_config_version` WHERE `target_code` = 'platform-config'"));
                assertEquals("[\"default\",\"tenant-a\"]", queryString(connection,
                    "SELECT `gray_tenants` FROM `ai_config_version` WHERE `target_code` = 'platform-config'"));
                assertEquals("customer-work-runtime-config-tenant-default", queryString(connection,
                    "SELECT `data_id` FROM `ai_config_version` "
                        + "WHERE `target_code` = 'v59-default-config'"));
                assertEquals("[\"default\",\"tenant-a\"]", queryString(connection,
                    "SELECT `gray_tenants` FROM `ai_config_version` "
                        + "WHERE `target_code` = 'v59-default-config'"));
                assertEquals("customer-work-runtime-config-tenant-default", queryString(connection,
                    "SELECT `data_id` FROM `ai_runtime_publish_task` WHERE `id` = 'platform-task'"));
                assertEquals("[\"default\",\"tenant-a\"]", queryString(connection,
                    "SELECT `gray_tenants` FROM `ai_runtime_publish_task` WHERE `id` = 'platform-task'"));
                assertEquals("customer-work-runtime-config-tenant-default", queryString(connection,
                    "SELECT `data_id` FROM `ai_runtime_publish_task` WHERE `id` = 'v59-default-task'"));
                assertEquals("[\"default\",\"tenant-a\"]", queryString(connection,
                    "SELECT `gray_tenants` FROM `ai_runtime_publish_task` WHERE `id` = 'v59-default-task'"));
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `flyway_schema_history` WHERE `version` = '63' AND `success` = 1"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v63ShouldFailBeforeAddingControlPlaneColumnOnUniqueConflict() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V63 真库迁移测试");
        String database = "admin_v63_conflict_" + randomSuffix();
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V63 真库迁移测试");

        try {
            migrate(database, "62");
            try (Connection connection = connection(database)) {
                execute(connection, "INSERT INTO `ai_chat_session_state` "
                    + "(`session_id`, `state_key`, `item_index`, `state_data`) VALUES "
                    + "('__platform__::probe:session', 'conflict-probe', 0, "
                    + "'{\"user_id\":\"__platform__::probe\"}')");
                execute(connection, "INSERT INTO `ai_workspace_session` "
                    + "(`tenant_id`, `agent_code`, `session_id`, `owner_user_id`, "
                    + "`created_at_ms`, `updated_at_ms`) VALUES "
                    + "('default', 'same-agent', 'same-session', 1, 1, 1), "
                    + "('__platform__', 'same-agent', 'same-session', 2, 1, 1)");
            }

            assertThrows(FlywayException.class, () -> migrate(database, null),
                "default 下已存在相同租户唯一键时必须中止迁移");
            try (Connection connection = connection(database)) {
                assertEquals(2, queryInt(connection,
                    "SELECT COUNT(*) FROM `ai_workspace_session` "
                        + "WHERE `agent_code` = 'same-agent' AND `session_id` = 'same-session'"));
                assertFalse(columnExists(connection, "sys_role", "control_plane"),
                    "冲突应在 ALTER TABLE 前失败，避免留下半完成结构");
                assertEquals("__platform__::probe:session", queryString(connection,
                    "SELECT `session_id` FROM `ai_chat_session_state` "
                        + "WHERE `state_key` = 'conflict-probe'"),
                    "冲突预检必须发生在第一张业务表更新之前");
            }
        } finally {
            dropDatabase(database);
        }
    }

    private void seedPlatformData(Connection connection) throws Exception {
        execute(connection, "INSERT INTO `sys_tenant` (`tenant_code`, `tenant_name`, `status`) "
            + "VALUES ('tenant-a', 'Tenant A', 'ACTIVE')");
        execute(connection, "INSERT INTO `sys_role` "
            + "(`role_name`, `role_code`, `status`, `tenant_id`, `data_scope`) VALUES "
            + "('审计员', 'auditor', 1, '__platform__', 'SELF'), "
            + "('租户角色', 'tenant-a-role', 1, 'tenant-a', 'SELF')");
        execute(connection, "INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `tenant_id`) "
            + "SELECT r.id, p.id, r.tenant_id FROM `sys_role` r JOIN `sys_permission` p "
            + "WHERE (r.role_code = 'auditor' AND p.perm_code IN ('menu:edit', 'billing:view', 'agent:edit')) "
            + "OR (r.role_code = 'operator' AND p.perm_code = 'menu:edit') "
            + "OR (r.role_code = 'tenant-a-role' AND p.perm_code = 'sensitive-word:edit')");
        execute(connection, "INSERT INTO `ai_chat_session_state` "
            + "(`session_id`, `state_key`, `item_index`, `state_data`) VALUES "
            + "('__platform__::assistant:session-1', 'platform-context', 0, "
            + "'{\"user_id\":\"__platform__::assistant\",\"marker\":\"kept\"}'), "
            + "('xxplatformyy::assistant:session-2', 'near-prefix-context', 0, "
            + "'{\"user_id\":\"xxplatformyy::assistant\"}')");
        execute(connection, "INSERT INTO `cw_agent_call_log` "
            + "(`request_id`, `user_id`, `username`, `agent_code`, `agent_name`, `session_id`, "
            + "`start_time`, `end_time`, `tenant_id`) VALUES "
            + "('platform-request', '__platform__::assistant', 'admin', 'assistant', "
            + "'Assistant', 'session-1', 1, 2, '__platform__'), "
            + "('near-prefix-request', 'xxplatformyy::assistant', 'admin', 'assistant', "
            + "'Assistant', 'session-2', 1, 2, '__platform__')");
        execute(connection, "INSERT INTO `ai_workspace_session` "
            + "(`tenant_id`, `agent_code`, `session_id`, `owner_user_id`, "
            + "`created_at_ms`, `updated_at_ms`) VALUES "
            + "('__platform__', 'platform-agent', 'session-1', 1, 1, 1), "
            + "('tenant-a', 'tenant-agent', 'session-2', 2, 1, 1)");
        execute(connection, "INSERT INTO `ai_config_version` "
            + "(`config_type`, `target_code`, `target_id`, `version`, `content`, `content_hash`, "
            + "`publish_scope`, `gray_tenants`, `data_id`, `status`, `tenant_id`) VALUES "
            + "('AGENT', 'platform-config', 1, 1, '{}', 'hash', 'GRAY', "
            + "'[\"__platform__\",\"tenant-a\"]', "
            + "'customer-work-runtime-config-tenant-__platform__', 'PUBLISHED', '__platform__'), "
            + "('AGENT', 'v59-default-config', 2, 1, '{}', 'hash-v59', 'GRAY', "
            + "'[\"__platform__\",\"tenant-a\"]', "
            + "'customer-work-runtime-config-tenant-__platform__', 'PUBLISHED', 'default')");
        execute(connection, "INSERT INTO `ai_runtime_publish_task` "
            + "(`id`, `tenant_id`, `target_id`, `data_id`, `revision`, `publish_scope`, "
            + "`gray_tenants`, `status`, `next_attempt_at_ms`, `lease_until_ms`, "
            + "`created_at_ms`, `updated_at_ms`) VALUES "
            + "('platform-task', '__platform__', 1, "
            + "'customer-work-runtime-config-tenant-__platform__', 'platform-revision', 'GRAY', "
            + "'[\"__platform__\",\"tenant-a\"]', 'PENDING', 0, 0, 1, 1), "
            + "('v59-default-task', 'default', 2, "
            + "'customer-work-runtime-config-tenant-__platform__', 'v59-default-revision', 'GRAY', "
            + "'[\"__platform__\",\"tenant-a\"]', 'PENDING', 0, 0, 1, 1)");
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

    private Set<String> tenantTables(Connection connection) throws Exception {
        String sql = "SELECT DISTINCT `table_name` FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND column_name = 'tenant_id'";
        Set<String> tables = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    private int countPlatformRows(Connection connection) throws Exception {
        int count = 0;
        for (String table : tenantTables(connection)) {
            count += queryInt(connection, "SELECT COUNT(*) FROM " + quoted(table)
                + " WHERE `tenant_id` = '__platform__'");
        }
        return count;
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private int queryInt(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
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
            // 清理失败不覆盖原始断言；随机库名不会影响业务库，残留可按 admin_v63_* 识别。
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

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
