package com.richard.fyoung.customeradmin.audit;

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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V64 真库迁移测试：补齐九张存量表的数据库自动维护审计时间。 */
class AuditTimestampMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    private static final long EXISTING_ROW_ID = 9_640_000_000_001L;
    private static final long NEW_ROW_ID = 9_640_000_000_002L;
    private static final LocalDateTime BASELINE_UPDATE_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final String RENAMED_LAST_TABLE = "cw_agent_call_segment_v64_preflight";
    private static final List<AuditTable> AUDIT_TABLES = List.of(
        new AuditTable("sys_user_role", "create_time", "update_time", 0),
        new AuditTable("sys_role_permission", "create_time", "update_time", 0),
        new AuditTable("ai_agent_mcp", "create_time", "update_time", 0),
        new AuditTable("ai_agent_skill", "create_time", "update_time", 0),
        new AuditTable("ai_agent_sub_agent", "create_time", "update_time", 0),
        new AuditTable("ai_agent_system_tool", "create_time", "update_time", 0),
        new AuditTable("ai_agent_knowledge_base", "create_time", "update_time", 0),
        new AuditTable("ai_scheduled_task_run", "create_time", "update_time", 0),
        new AuditTable("cw_agent_call_segment", "created_at", "updated_at", 3));
    private static final Set<String> TARGET_TABLES = AUDIT_TABLES.stream()
        .map(AuditTable::table)
        .collect(Collectors.toUnmodifiableSet());

    @Test
    void v64ShouldCoverExactlyNineTablesAndMatchDbaMirror() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/migration/V64__add_audit_timestamps.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher matcher = Pattern.compile("CONCAT\\('ALTER TABLE `([^`]+)` '").matcher(sql);
        Set<String> alteredTables = new HashSet<>();
        int statementCount = 0;
        while (matcher.find()) {
            alteredTables.add(matcher.group(1));
            statementCount++;
        }

        assertTrue(sql.startsWith("SET NAMES utf8mb4;\n"), "含中文注释的手工镜像必须显式声明字符集");
        assertTrue(sql.contains("@v64_table_count = 9"), "任何 ALTER 前必须预检九张目标表全部存在");
        assertEquals(9, statementCount, "V64 必须且只能修改九张目标表");
        assertEquals(TARGET_TABLES, alteredTables, "V64 目标表集合必须与扫描结果一致");
        for (AuditTable table : AUDIT_TABLES) {
            assertSqlDefinition(sql, table);
        }

        String mirrorSql = Files.readString(repositoryRoot().resolve(
            "mysql/02-customer-admin/64-V64__add_audit_timestamps.sql"));
        assertEquals(sql, mirrorSql, "Flyway 迁移与 DBA 镜像必须逐字一致");
    }

    @Test
    void v64ShouldBackfillDefaultsAndAdvanceUpdateTimestamp() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V64 真库迁移测试");
        String database = "admin_v64_" + randomSuffix();
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V64 真库迁移测试");

        try {
            migrate(database, "63");
            try (Connection connection = connection(database)) {
                assertEquals(TARGET_TABLES, tablesMissingBothAuditTimestamps(connection),
                    "V63 快照中同时缺少两类审计时间的表必须与扫描清单一致");
                for (AuditTable table : AUDIT_TABLES) {
                    assertFalse(columnExists(connection, table.table(), table.createdColumn()),
                        table.table() + " 在 V63 不应已有创建时间");
                    assertFalse(columnExists(connection, table.table(), table.updatedColumn()),
                        table.table() + " 在 V63 不应已有修改时间");
                    insertRow(connection, table.table(), EXISTING_ROW_ID);
                }
            }

            migrate(database, "64");

            try (Connection connection = connection(database)) {
                Map<String, AuditTimes> existingTimes = new HashMap<>();
                for (AuditTable table : AUDIT_TABLES) {
                    assertColumnDefinition(connection, table, table.createdColumn(), false);
                    assertColumnDefinition(connection, table, table.updatedColumn(), true);

                    AuditTimes backfilled = auditTimes(connection, table, EXISTING_ROW_ID);
                    assertNotNull(backfilled.createdAt(), table.table() + " 存量行创建时间必须回填");
                    assertNotNull(backfilled.updatedAt(), table.table() + " 存量行修改时间必须回填");

                    insertRow(connection, table.table(), NEW_ROW_ID);
                    AuditTimes defaults = auditTimes(connection, table, NEW_ROW_ID);
                    assertNotNull(defaults.createdAt(), table.table() + " 新增行创建时间必须由数据库生成");
                    assertNotNull(defaults.updatedAt(), table.table() + " 新增行修改时间必须由数据库生成");

                    setBaselineUpdateTime(connection, table, EXISTING_ROW_ID);
                    AuditTimes baseline = auditTimes(connection, table, EXISTING_ROW_ID);
                    assertEquals(backfilled.createdAt(), baseline.createdAt(),
                        table.table() + " 显式设置修改时间不得改写创建时间");
                    assertEquals(BASELINE_UPDATE_TIME, baseline.updatedAt(),
                        table.table() + " 必须接受显式修改时间作为推进前基线");
                    existingTimes.put(table.table(), baseline);
                }

                for (AuditTable table : AUDIT_TABLES) {
                    updateTenant(connection, table.table(), EXISTING_ROW_ID);
                }

                for (AuditTable table : AUDIT_TABLES) {
                    AuditTimes before = existingTimes.get(table.table());
                    AuditTimes after = auditTimes(connection, table, EXISTING_ROW_ID);
                    assertEquals(before.createdAt(), after.createdAt(),
                        table.table() + " 真实 UPDATE 不得改写创建时间");
                    assertTrue(after.updatedAt().isAfter(before.updatedAt()),
                        table.table() + " 真实 UPDATE 必须推进修改时间");
                }

                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `flyway_schema_history` "
                        + "WHERE `version` = '64' AND `success` = 1"));
                assertTrue(tablesMissingBothAuditTimestamps(connection).isEmpty(),
                    "V64 完成后不得残留同时缺少两类审计时间的后台业务表");
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v64ShouldPreflightAllTablesAndResumeSafelyAfterRepair() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 V64 真库迁移测试");
        String database = "admin_v64_retry_" + randomSuffix();
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过 V64 真库迁移测试");

        AuditTable firstTable = AUDIT_TABLES.get(0);
        AuditTable secondTable = AUDIT_TABLES.get(1);
        AuditTable thirdTable = AUDIT_TABLES.get(2);
        AuditTable lastTable = AUDIT_TABLES.get(AUDIT_TABLES.size() - 1);
        try {
            migrate(database, "63");
            Map<String, Integer> columnsBeforeFailedMigration = new HashMap<>();
            try (Connection connection = connection(database)) {
                addAuditColumn(connection, firstTable, firstTable.createdColumn(), false);
                addAuditColumn(connection, firstTable, firstTable.updatedColumn(), true);
                addAuditColumn(connection, secondTable, secondTable.createdColumn(), false);
                addAuditColumn(connection, thirdTable, thirdTable.updatedColumn(), true);
                renameTable(connection, lastTable.table(), RENAMED_LAST_TABLE);

                for (AuditTable table : AUDIT_TABLES) {
                    String actualTable = table.equals(lastTable) ? RENAMED_LAST_TABLE : table.table();
                    columnsBeforeFailedMigration.put(actualTable,
                        auditColumnCount(connection, actualTable, table));
                }
            }

            assertThrows(FlywayException.class, () -> migrate(database, "64"),
                "缺少任一目标表时 V64 必须在任何 ALTER 前失败");

            try (Connection connection = connection(database)) {
                assertFalse(tableExists(connection, lastTable.table()), "被临时改名的目标表应保持缺失");
                for (AuditTable table : AUDIT_TABLES) {
                    String actualTable = table.equals(lastTable) ? RENAMED_LAST_TABLE : table.table();
                    assertEquals(columnsBeforeFailedMigration.get(actualTable),
                        auditColumnCount(connection, actualTable, table),
                        table.table() + " 预检失败后不得发生额外 DDL");
                }
                assertEquals(0, queryInt(connection,
                    "SELECT COUNT(*) FROM `flyway_schema_history` "
                        + "WHERE `version` = '64' AND `success` = 1"));
                renameTable(connection, RENAMED_LAST_TABLE, lastTable.table());
            }

            repair(database);
            migrate(database, "64");

            try (Connection connection = connection(database)) {
                for (AuditTable table : AUDIT_TABLES) {
                    assertColumnDefinition(connection, table, table.createdColumn(), false);
                    assertColumnDefinition(connection, table, table.updatedColumn(), true);
                }
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `flyway_schema_history` WHERE `version` = '64'"),
                    "repair 后 V64 只能保留一条历史记录");
                assertEquals(1, queryInt(connection,
                    "SELECT COUNT(*) FROM `flyway_schema_history` "
                        + "WHERE `version` = '64' AND `success` = 1"),
                    "repair 重试后的 V64 必须成功");
            }
        } finally {
            dropDatabase(database);
        }
    }

    private void assertSqlDefinition(String sql, AuditTable table) {
        Pattern pattern = Pattern.compile("(?s)-- target: " + Pattern.quote(table.table())
            + "\\R(.*?)DEALLOCATE PREPARE v64_stmt;");
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), table.table() + " 缺少动态 ALTER TABLE");
        String statement = matcher.group().replaceAll("\\s+", " ");
        String precision = table.precision() == 0 ? "" : "(" + table.precision() + ")";
        assertTrue(statement.contains("ADD COLUMN `" + table.createdColumn() + "` DATETIME"
            + precision + " NOT NULL DEFAULT CURRENT_TIMESTAMP" + precision + " COMMENT ''创建时间''"),
            table.table() + " 创建时间定义不完整");
        assertTrue(statement.contains("ADD COLUMN `" + table.updatedColumn() + "` DATETIME"
            + precision + " NOT NULL DEFAULT CURRENT_TIMESTAMP" + precision
            + " ON UPDATE CURRENT_TIMESTAMP" + precision + " COMMENT ''修改时间''"),
            table.table() + " 修改时间定义不完整");
        assertTrue(statement.contains("table_name = '" + table.table() + "'"),
            table.table() + " 必须按 information_schema 实际列状态生成 ALTER");
    }

    private void assertColumnDefinition(Connection connection, AuditTable table,
                                        String column, boolean updatedColumn) throws Exception {
        String sql = "SELECT `data_type`, `datetime_precision`, `is_nullable`, `column_default`, "
            + "`extra`, `column_comment` FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.table());
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), table.table() + "." + column + " 必须存在");
                assertEquals("datetime", resultSet.getString("data_type"));
                assertEquals(table.precision(), resultSet.getInt("datetime_precision"));
                assertEquals("NO", resultSet.getString("is_nullable"));
                assertTrue(resultSet.getString("column_default").toLowerCase(Locale.ROOT)
                    .startsWith("current_timestamp"), table.table() + "." + column + " 必须有数据库默认值");
                String extra = resultSet.getString("extra").toLowerCase(Locale.ROOT);
                if (updatedColumn) {
                    assertTrue(extra.contains("on update current_timestamp"),
                        table.table() + "." + column + " 必须由数据库自动更新");
                    assertEquals("修改时间", resultSet.getString("column_comment"));
                } else {
                    assertFalse(extra.contains("on update current_timestamp"),
                        table.table() + "." + column + " 不得随更新改变");
                    assertEquals("创建时间", resultSet.getString("column_comment"));
                }
                assertFalse(resultSet.next(), table.table() + "." + column + " 不得重复");
            }
        }
    }

    private void insertRow(Connection connection, String table, long id) throws Exception {
        String sql = switch (table) {
            case "sys_user_role" -> "INSERT INTO `sys_user_role` "
                + "(`id`, `user_id`, `role_id`, `tenant_id`) VALUES (" + id + ", " + id
                + ", " + (id + 1) + ", 'default')";
            case "sys_role_permission" -> "INSERT INTO `sys_role_permission` "
                + "(`id`, `role_id`, `permission_id`, `tenant_id`) VALUES (" + id + ", " + id
                + ", " + (id + 1) + ", 'default')";
            case "ai_agent_mcp" -> "INSERT INTO `ai_agent_mcp` "
                + "(`id`, `agent_id`, `mcp_id`, `tenant_id`) VALUES (" + id + ", " + id
                + ", " + (id + 1) + ", 'default')";
            case "ai_agent_skill" -> "INSERT INTO `ai_agent_skill` "
                + "(`id`, `agent_id`, `skill_id`, `tenant_id`) VALUES (" + id + ", " + id
                + ", " + (id + 1) + ", 'default')";
            case "ai_agent_sub_agent" -> "INSERT INTO `ai_agent_sub_agent` "
                + "(`id`, `agent_id`, `sub_agent_id`, `tenant_id`) VALUES (" + id + ", " + id
                + ", " + (id + 1) + ", 'default')";
            case "ai_agent_system_tool" -> "INSERT INTO `ai_agent_system_tool` "
                + "(`id`, `agent_id`, `system_tool_id`, `tenant_id`) VALUES (" + id + ", " + id
                + ", " + (id + 1) + ", 'default')";
            case "ai_agent_knowledge_base" -> "INSERT INTO `ai_agent_knowledge_base` "
                + "(`id`, `agent_id`, `knowledge_base_id`, `tenant_id`) VALUES (" + id + ", " + id
                + ", " + (id + 1) + ", 'default')";
            case "ai_scheduled_task_run" -> "INSERT INTO `ai_scheduled_task_run` "
                + "(`id`, `task_id`, `task_code`, `trigger_type`, `start_time`, `status`, `tenant_id`) "
                + "VALUES (" + id + ", " + id + ", 'audit-" + id
                + "', 'MANUAL', '2026-01-01 00:00:00', 'SUCCESS', 'default')";
            case "cw_agent_call_segment" -> "INSERT INTO `cw_agent_call_segment` "
                + "(`id`, `call_log_id`, `seq`, `kind`, `name`, `start_time`, `tenant_id`) "
                + "VALUES (" + id + ", " + id + ", 1, 'MODEL', 'audit', 1, 'default')";
            default -> throw new IllegalArgumentException("unsupported audit table: " + table);
        };
        execute(connection, sql);
    }

    private AuditTimes auditTimes(Connection connection, AuditTable table, long id) throws Exception {
        String sql = "SELECT " + quoted(table.createdColumn()) + ", " + quoted(table.updatedColumn())
            + " FROM " + quoted(table.table()) + " WHERE `id` = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), table.table() + " 测试行必须存在");
                Timestamp createdAt = resultSet.getTimestamp(1);
                Timestamp updatedAt = resultSet.getTimestamp(2);
                return new AuditTimes(
                    createdAt == null ? null : createdAt.toLocalDateTime(),
                    updatedAt == null ? null : updatedAt.toLocalDateTime());
            }
        }
    }

    private void updateTenant(Connection connection, String table, long id) throws Exception {
        String sql = "UPDATE " + quoted(table) + " SET `tenant_id` = 'audit-updated' WHERE `id` = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            assertEquals(1, statement.executeUpdate(), table + " 必须真实更新一行");
        }
    }

    private void setBaselineUpdateTime(Connection connection, AuditTable table, long id) throws Exception {
        String sql = "UPDATE " + quoted(table.table()) + " SET " + quoted(table.updatedColumn())
            + " = ? WHERE `id` = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(BASELINE_UPDATE_TIME));
            statement.setLong(2, id);
            assertEquals(1, statement.executeUpdate(), table.table() + " 必须设置一行修改时间基线");
        }
    }

    private void addAuditColumn(Connection connection, AuditTable table,
                                String column, boolean updatedColumn) throws Exception {
        String precision = table.precision() == 0 ? "" : "(" + table.precision() + ")";
        String onUpdate = updatedColumn
            ? " ON UPDATE CURRENT_TIMESTAMP" + precision + " COMMENT '修改时间'"
            : " COMMENT '创建时间'";
        execute(connection, "ALTER TABLE " + quoted(table.table()) + " ADD COLUMN " + quoted(column)
            + " DATETIME" + precision + " NOT NULL DEFAULT CURRENT_TIMESTAMP" + precision + onUpdate);
    }

    private int auditColumnCount(Connection connection, String actualTable,
                                 AuditTable auditTable) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name IN (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actualTable);
            statement.setString(2, auditTable.createdColumn());
            statement.setString(3, auditTable.updatedColumn());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        String sql = "SELECT 1 FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private Set<String> tablesMissingBothAuditTimestamps(Connection connection) throws Exception {
        String sql = "SELECT t.`table_name` FROM information_schema.tables t "
            + "WHERE t.table_schema = DATABASE() AND t.table_type = 'BASE TABLE' "
            + "AND t.table_name <> 'flyway_schema_history' "
            + "AND NOT EXISTS (SELECT 1 FROM information_schema.columns c "
            + "WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name "
            + "AND c.column_name IN ('create_time', 'created_at', 'created_at_ms')) "
            + "AND NOT EXISTS (SELECT 1 FROM information_schema.columns c "
            + "WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name "
            + "AND c.column_name IN ('update_time', 'updated_at', 'updated_at_ms'))";
        Set<String> tables = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    private void renameTable(Connection connection, String source, String target) throws Exception {
        execute(connection, "RENAME TABLE " + quoted(source) + " TO " + quoted(target));
    }

    private void migrate(String database, String target) {
        flyway(database, target).migrate();
    }

    private void repair(String database) {
        flyway(database, "64").repair();
    }

    private Flyway flyway(String database, String target) {
        FluentConfiguration configuration = Flyway.configure()
            .dataSource(jdbcUrl(database), USERNAME, PASSWORD)
            .locations("classpath:db/migration")
            .placeholderReplacement(false)
            .target(target);
        return configuration.load();
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
            // 清理失败不覆盖原始断言；随机库名不会影响业务库，残留可按 admin_v64_* 识别。
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

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
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

    private record AuditTable(String table, String createdColumn, String updatedColumn, int precision) {
    }

    private record AuditTimes(LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
