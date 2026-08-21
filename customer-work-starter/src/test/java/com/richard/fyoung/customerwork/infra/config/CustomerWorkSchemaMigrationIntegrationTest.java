package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.infra.migration.V2__ReconcileLegacySchema;
import com.richard.fyoung.customerwork.infra.migration.V9__AddAuditTimestamps;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 客服端业务库 Flyway 门控测试：同时覆盖空库首次初始化与旧初始化方式创建的存量库接管。
 *
 * <p>每次创建带随机后缀的隔离数据库，结束后只删除本用例创建的库，不接触默认业务库。MySQL 不可达或
 * 测试账号无建库权限时自动跳过。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkSchemaMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private static final String DEFAULT_TENANT = "default";
    private static final List<String> AUDIT_TIMESTAMP_TABLES = List.of(
        "cw_slot_filling_progress", "cw_dialog_stage", "cw_agent_call_segment", "cw_product",
        "cw_member", "cw_knowledge", "cw_fact_log", "cw_prompt_version", "cw_csat_survey",
        "cw_knowledge_gap");

    private static final Set<String> TENANT_TABLES = Set.of(
        "cw_agent_call_log", "cw_agent_call_segment", "cw_approval", "cw_audit_log",
        "cw_badcase", "cw_chat_attachment", "cw_chat_message", "cw_complaint",
        "cw_csat_survey", "cw_dead_letter", "cw_dialog_stage", "cw_dict_item",
        "cw_dict_type", "cw_eval_case", "cw_eval_run", "cw_fact_log",
        "cw_handoff_ticket", "cw_harness_memory", "cw_invoice_request", "cw_knowledge",
        "cw_knowledge_gap", "cw_long_term_memory", "cw_member", "cw_member_account_log",
        "cw_message_feedback", "cw_order", "cw_outbox_message", "cw_product",
        "cw_prompt_version", "cw_rate_limit_rule", "cw_refund", "cw_seat_agent",
        "cw_semantic_cache", "cw_sensitive_word", "cw_sensitive_word_hit_log", "cw_skill",
        "cw_skill_file", "cw_slot_filling_progress", "cw_subject_quota_hit",
        "cw_subject_quota_level", "cw_tenant_quota", "cw_ticket", "cw_ticket_event", "cw_user");
    private static final Set<String> CONFLICT_TABLES = Set.of(
        "cw_user", "cw_knowledge", "cw_sensitive_word", "cw_rate_limit_rule", "cw_dict_type",
        "cw_dict_item", "cw_tenant_quota", "cw_long_term_memory", "cw_harness_memory", "cw_skill",
        "cw_eval_case", "cw_knowledge_gap", "cw_subject_quota_level");

    @Test
    void v7ShouldExplicitlyCoverEveryTenantTable() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/customerwork/migration/V7__consolidate_platform_tenant_to_default.sql")
            .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Pattern statementPattern = Pattern.compile("(?ms)^UPDATE\\s+`(cw_[^`]+)`.*?;");
        Matcher matcher = statementPattern.matcher(sql);
        Set<String> updatedTables = new HashSet<>();
        while (matcher.find()) {
            updatedTables.add(matcher.group(1));
            assertTrue(matcher.group().contains("WHERE `tenant_id` = '__platform__'"),
                () -> matcher.group(1) + " 只能更新历史平台租户行");
        }

        assertEquals(TENANT_TABLES, updatedTables, "V7 必须显式覆盖全部 44 张租户表");
        assertEquals(CONFLICT_TABLES, extractConflictTables(sql), "V7 必须前置预检 13 类租户唯一键");
        assertTrue(sql.indexOf("DROP TEMPORARY TABLE `_v7_platform_tenant_conflict_guard`")
            < sql.indexOf("UPDATE `cw_agent_call_log`"), "冲突预检必须早于全部业务写入");
        assertFalse(sql.matches("(?ims).*^\\s*UPDATE\\s+IGNORE.*"),
            "唯一冲突不得被 UPDATE IGNORE 吞掉");
        assertFalse(sql.matches("(?ims).*^\\s*REPLACE\\s+INTO.*"),
            "唯一冲突不得被 REPLACE 吞掉");
        assertFalse(sql.matches("(?ims).*^\\s*DELETE\\s+FROM.*"), "V7 不得静默删除冲突数据");
    }

    private Set<String> extractConflictTables(String sql) {
        Pattern pattern = Pattern.compile("(?m)^\\s*INNER JOIN\\s+`(cw_[^`]+)`\\s+d$");
        Matcher matcher = pattern.matcher(sql);
        Set<String> tables = new HashSet<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

    @Test
    void migrate_shouldInitializeEmptyDatabaseAndReconcileLegacyDatabase() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过客服端 Flyway 门控测试");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String emptyDatabase = "cw_flyway_empty_" + suffix;
        String legacyDatabase = "cw_flyway_legacy_" + suffix;
        String mirrorDatabase = "cw_flyway_mirror_" + suffix;
        String platformDatabase = "cw_flyway_platform_" + suffix;
        assumeTrue(canCreateDatabases(emptyDatabase, legacyDatabase, mirrorDatabase, platformDatabase),
            "MySQL 测试账号无建库权限，跳过");

        try {
            verifyEmptyDatabaseMigration(emptyDatabase);
            verifyLegacyDatabaseMigration(legacyDatabase);
            verifyCompleteMirrorAdoption(mirrorDatabase);
            verifyPlatformTenantMigration(platformDatabase);
        } finally {
            dropDatabase(emptyDatabase);
            dropDatabase(legacyDatabase);
            dropDatabase(mirrorDatabase);
            dropDatabase(platformDatabase);
        }
    }

    @Test
    void v7ShouldFailFastOnUniqueConflict() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过客服端 Flyway 门控测试");
        String database = "cw_flyway_conflict_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(canCreateDatabases(database), "MySQL 测试账号无建库权限，跳过");

        try (HikariDataSource dataSource = dataSource(database, "flyway-conflict-test")) {
            migrateTo(dataSource, "6");
            execute(dataSource, "INSERT INTO `cw_agent_call_log` "
                + "(`tenant_id`, `request_id`, `user_id`, `username`, `agent_code`, `agent_name`, "
                + "`session_id`, `start_time`, `end_time`) VALUES "
                + "('__platform__', 'conflict-probe', '__platform__::assistant', 'admin', "
                + "'assistant', 'Assistant', 'session-conflict', 1, 2)");
            execute(dataSource, "INSERT INTO `cw_user` "
                + "(`tenant_id`, `id`, `username`, `password_hash`, `created_at_ms`) VALUES "
                + "('default', 'default-user', 'same-name', 'hash', 1), "
                + "('__platform__', 'platform-user', 'same-name', 'hash', 1)");

            assertThrows(IllegalStateException.class, () -> migrate(dataSource, database),
                "default 下已存在相同租户唯一键时必须中止迁移");
            assertEquals(2, queryInt(dataSource,
                "SELECT COUNT(*) FROM `cw_user` WHERE `username` = 'same-name'"));
            assertEquals(1, queryInt(dataSource,
                "SELECT COUNT(*) FROM `cw_user` WHERE `tenant_id` = '__platform__'"));
            assertEquals(1, queryInt(dataSource,
                "SELECT COUNT(*) FROM `cw_agent_call_log` "
                    + "WHERE `request_id` = 'conflict-probe' "
                    + "AND `tenant_id` = '__platform__' "
                    + "AND `user_id` = '__platform__::assistant'"),
                "冲突预检必须发生在第一张业务表更新之前");
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v8ShouldFailFastBeforeWritingWhenLongTermMemoryRehashConflicts() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过客服端 Flyway 门控测试");
        String database = "cw_flyway_scope_conflict_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(canCreateDatabases(database), "MySQL 测试账号无建库权限，跳过");

        try (HikariDataSource dataSource = dataSource(database, "flyway-scope-conflict-test")) {
            migrateTo(dataSource, "7");
            execute(dataSource, "INSERT INTO `cw_long_term_memory` "
                + "(`tenant_id`, `scope_id`, `fact`, `scope_hash`, `created_at_ms`) VALUES "
                + "('default', 'default', 'same fact', "
                + "LOWER(SHA2(CONCAT('default', CHAR(10), 'same fact'), 256)), 1), "
                + "('default', '__platform__', 'same fact', "
                + "LOWER(SHA2(CONCAT('__platform__', CHAR(10), 'same fact'), 256)), 2)");
            execute(dataSource, "INSERT INTO `cw_fact_log` "
                + "(`tenant_id`, `scope_id`, `fact`, `ts`) "
                + "VALUES ('default', '__platform__', 'scope-conflict-probe', 1)");
            execute(dataSource, "INSERT INTO `cw_semantic_cache` "
                + "(`tenant_id`, `scope_id`, `intent`, `question`, `question_vector`, `answer`, "
                + "`created_at_ms`, `last_hit_at_ms`) VALUES "
                + "('default', '__platform__', 'consult', 'scope-conflict-question', '0.1,0.2', "
                + "'scope-conflict-answer', 1, 1)");

            assertThrows(IllegalStateException.class, () -> migrate(dataSource, database),
                "scope 归一后的长期记忆 hash 冲突必须中止 V8");
            assertEquals(1, queryInt(dataSource,
                "SELECT COUNT(*) FROM `cw_long_term_memory` "
                    + "WHERE `tenant_id` = 'default' AND `scope_id` = '__platform__' "
                    + "AND `scope_hash` = LOWER(SHA2(CONCAT('__platform__', CHAR(10), 'same fact'), 256))"),
                "冲突预检不得提前改写长期记忆 scope/hash");
            assertEquals(1, queryInt(dataSource,
                "SELECT COUNT(*) FROM `cw_fact_log` WHERE `scope_id` = '__platform__'"),
                "长期记忆冲突时不得部分更新事实日志");
            assertEquals(1, queryInt(dataSource,
                "SELECT COUNT(*) FROM `cw_semantic_cache` WHERE `scope_id` = '__platform__'"),
                "长期记忆冲突时不得部分更新语义缓存");
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v9ShouldFailFastAndRecoverAfterRepair() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过客服端 Flyway 门控测试");
        String database = "cw_flyway_audit_missing_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(canCreateDatabases(database), "MySQL 测试账号无建库权限，跳过");

        try (HikariDataSource dataSource = dataSource(database, "flyway-audit-missing-test")) {
            migrateTo(dataSource, "8");
            assertEquals(new HashSet<>(AUDIT_TIMESTAMP_TABLES), tablesMissingBothAuditTimestamps(dataSource),
                "V8 快照中同时缺少两类审计时间的表必须与扫描清单一致");

            execute(dataSource, "ALTER TABLE `cw_slot_filling_progress` "
                + "ADD COLUMN `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                + "COMMENT '记录创建时间'");
            execute(dataSource, "ALTER TABLE `cw_dialog_stage` "
                + "ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                + "ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间'");
            execute(dataSource, "RENAME TABLE `cw_knowledge_gap` TO `cw_knowledge_gap_v9_missing`");

            assertThrows(IllegalStateException.class, () -> migrate(dataSource, database),
                "任一目标表缺失时 V9 必须中止迁移");
            assertFalse(columnExists(dataSource, "cw_slot_filling_progress", "updated_at"),
                "缺表预检不得补齐仅有创建时间的表");
            assertFalse(columnExists(dataSource, "cw_dialog_stage", "created_at"),
                "缺表预检不得补齐仅有修改时间的表");
            assertFalse(columnExists(dataSource, "cw_product", "created_at"),
                "缺表预检必须早于第一张表的 DDL，避免不可回滚的部分迁移");

            execute(dataSource, "RENAME TABLE `cw_knowledge_gap_v9_missing` TO `cw_knowledge_gap`");
            repair(dataSource);
            migrate(dataSource, database);

            assertAuditTimestampColumns(dataSource);
            assertTrue(tablesMissingBothAuditTimestamps(dataSource).isEmpty(),
                "V9 完成后不得残留同时缺少两类审计时间的客服端业务表");
            assertEquals(1, countHistoryVersion(dataSource, "9"), "repair 后重试只应登记一条成功的 V9");
            assertEquals("9", latestHistoryVersion(dataSource));
        } finally {
            dropDatabase(database);
        }
    }

    private void verifyCompleteMirrorAdoption(String database) throws Exception {
        try (HikariDataSource dataSource = dataSource(database, "flyway-mirror-test")) {
            Path workingDirectory = Path.of("").toAbsolutePath();
            Path repositoryRoot = Files.isDirectory(workingDirectory.resolve("mysql"))
                ? workingDirectory : workingDirectory.getParent();
            Path mirrorPath = repositoryRoot.resolve(
                "mysql/01-agent-scope-customer-work/customer-work-schema.sql");
            String mirrorSql = Files.readString(mirrorPath, StandardCharsets.UTF_8)
                .replaceFirst("(?is)CREATE DATABASE IF NOT EXISTS .*?;", "")
                .replaceFirst("(?is)USE\\s+`[^`]+`\\s*;", "");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ByteArrayResource(mirrorSql.getBytes(StandardCharsets.UTF_8)));
            populator.execute(dataSource);

            migrate(dataSource, database);
            migrate(dataSource, database);

            assertEquals(44, countBusinessTables(dataSource));
            assertTrue(columnExists(dataSource, "cw_dead_letter", "lease_owner"));
            assertTrue(columnExists(dataSource, "cw_outbox_message", "lease_owner"));
            assertAuditTimestampColumns(dataSource);
            // 接管基线 1 行 + 重跑的 V6/V7/V8/V9 各 1 行。四者都是幂等迁移，
            // 故刻意不给它们加镜像判定——为省历史行去写脆弱的数据猜测得不偿失
            assertEquals(5, countHistoryRows(dataSource), "完整镜像只应登记一次接管基线");
            // V5 是纯种子迁移，镜像里已带那两档，故接管版本要跟到 5——
            // 停在 4 的话 Flyway 会重跑 V5，撞唯一键直接失败（判定见 resolveBaselineVersion）
            assertEquals(1, countHistoryVersion(dataSource, "5"), "完整镜像应从当前版本接管");
            assertEquals(1, countHistoryVersion(dataSource, "6"), "幂等迁移重跑一次，两次 migrate 也只记一条");
            assertEquals(1, countHistoryVersion(dataSource, "7"), "平台租户归一迁移应登记一次");
            assertEquals(1, countHistoryVersion(dataSource, "8"), "遗留平台 scope 归一迁移应登记一次");
            assertEquals(1, countHistoryVersion(dataSource, "9"), "审计时间迁移应登记一次");
            assertEquals("9", latestHistoryVersion(dataSource));
        }
    }

    private void verifyEmptyDatabaseMigration(String database) throws Exception {
        try (HikariDataSource dataSource = dataSource(database, "flyway-empty-test")) {
            migrate(dataSource, database);
            assertEquals(44, countBusinessTables(dataSource));
            // V5（ADMIN_USER 档种子）、V6（运营分区归一）、V7/V8（平台租户与 scope 归一）、V9（审计列）都不建表
            assertEquals(9, countHistoryRows(dataSource));
            assertTrue(columnExists(dataSource, "cw_dead_letter", "lease_owner"));
            assertAuditTimestampColumns(dataSource);
            verifyUpdatedAtAutoAdvance(dataSource);
            assertEquals(0, countHistoryVersion(dataSource, "0"), "空库不应写 baseline 记录");
            assertEquals(1, countHistoryVersion(dataSource, "9"));
            assertEquals("9", latestHistoryVersion(dataSource));
        }
    }

    private void verifyLegacyDatabaseMigration(String database) throws Exception {
        try (HikariDataSource dataSource = dataSource(database, "flyway-legacy-test")) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/customerwork/migration/V1__baseline.sql"));
            populator.execute(dataSource);
            execute(dataSource, "ALTER TABLE `cw_user` DROP COLUMN `avatar_url`");
            execute(dataSource, "ALTER TABLE `cw_chat_attachment` DROP COLUMN `message_id`");

            migrate(dataSource, database);

            assertTrue(columnExists(dataSource, "cw_user", "avatar_url"));
            assertTrue(columnExists(dataSource, "cw_chat_attachment", "message_id"));
            // V4 给存量 cw_user 加的配额等级列：这张表是 V1 就建好的，加列只能靠迁移补
            assertTrue(columnExists(dataSource, "cw_user", "level_code"));
            assertEquals(44, countBusinessTables(dataSource));
            assertAuditTimestampColumns(dataSource);
            // baseline 0 + V1~V9
            assertEquals(10, countHistoryRows(dataSource));
            assertEquals(1, countHistoryVersion(dataSource, "0"), "非空存量库必须先登记 baseline 0");
            assertEquals(1, countHistoryVersion(dataSource, "9"));
            assertEquals("9", latestHistoryVersion(dataSource));
        }
    }

    private void verifyPlatformTenantMigration(String database) throws Exception {
        try (HikariDataSource dataSource = dataSource(database, "flyway-platform-test")) {
            migrateTo(dataSource, "6");
            assertEquals(TENANT_TABLES, tenantTables(dataSource), "V6 后应恰有 44 张租户表");

            execute(dataSource, "INSERT INTO `cw_agent_call_log` "
                + "(`tenant_id`, `request_id`, `user_id`, `username`, `agent_code`, `agent_name`, "
                + "`session_id`, `start_time`, `end_time`) VALUES "
                + "('__platform__', 'platform-request', '__platform__::assistant', 'admin', "
                + "'assistant', 'Assistant', 'session-1', 1, 2), "
                + "('__platform__', 'near-prefix-request', 'xxplatformyy::assistant', 'admin', "
                + "'assistant', 'Assistant', 'session-2', 1, 2)");
            execute(dataSource, "INSERT INTO `cw_csat_survey` "
                + "(`tenant_id`, `session_id`, `scope_id`, `score`, `invited_at_ms`) VALUES "
                + "('__platform__', 'platform-csat', '__platform__', 5, 1)");
            execute(dataSource, "INSERT INTO `cw_knowledge_gap` "
                + "(`tenant_id`, `question_hash`, `question`, `scope_id`, `miss_count`, "
                + "`first_seen_at_ms`, `last_seen_at_ms`) VALUES "
                + "('__platform__', 'platform-hash', 'missing answer', '__platform__', 3, 1, 2)");
            execute(dataSource, "INSERT INTO `cw_outbox_message` "
                + "(`tenant_id`, `id`, `type`, `aggregate_id`, `payload`, `next_attempt_at_ms`, `created_at_ms`) "
                + "VALUES ('__platform__', 'platform-outbox', 'TEST', 'aggregate-1', '{}', 1, 1), "
                + "('tenant-a', 'tenant-outbox', 'TEST', 'aggregate-2', '{}', 1, 1)");
            execute(dataSource, "INSERT INTO `cw_long_term_memory` "
                + "(`tenant_id`, `scope_id`, `fact`, `scope_hash`, `created_at_ms`) VALUES "
                + "('__platform__', '__platform__', 'platform fact', "
                + "LOWER(SHA2(CONCAT('__platform__', CHAR(10), 'platform fact'), 256)), 1), "
                + "('__platform__', 'xxplatformyy', 'near-prefix fact', "
                + "LOWER(SHA2(CONCAT('xxplatformyy', CHAR(10), 'near-prefix fact'), 256)), 1)");
            execute(dataSource, "INSERT INTO `cw_fact_log` "
                + "(`tenant_id`, `scope_id`, `fact`, `ts`) VALUES "
                + "('__platform__', '__platform__', 'platform fact log', 1), "
                + "('__platform__', 'xxplatformyy', 'near-prefix fact log', 1)");
            execute(dataSource, "INSERT INTO `cw_semantic_cache` "
                + "(`tenant_id`, `scope_id`, `intent`, `question`, `question_vector`, `answer`, "
                + "`created_at_ms`, `last_hit_at_ms`) VALUES "
                + "('__platform__', '__platform__', 'consult', 'platform question', '0.1,0.2', "
                + "'platform answer', 1, 1), "
                + "('__platform__', 'xxplatformyy', 'consult', 'near-prefix question', '0.2,0.3', "
                + "'near-prefix answer', 1, 1)");

            migrate(dataSource, database);

            assertEquals(0, countPlatformRows(dataSource), "44 张租户表不得残留平台租户行");
            assertEquals(DEFAULT_TENANT, queryString(dataSource,
                "SELECT `tenant_id` FROM `cw_agent_call_log` WHERE `request_id` = 'platform-request'"));
            assertEquals("default::assistant", queryString(dataSource,
                "SELECT `user_id` FROM `cw_agent_call_log` WHERE `request_id` = 'platform-request'"));
            assertEquals("xxplatformyy::assistant", queryString(dataSource,
                "SELECT `user_id` FROM `cw_agent_call_log` WHERE `request_id` = 'near-prefix-request'"),
                "下划线必须按字面量匹配，不能按 LIKE 单字符通配符误改近似前缀");
            assertEquals(DEFAULT_TENANT, queryString(dataSource,
                "SELECT `scope_id` FROM `cw_csat_survey` WHERE `session_id` = 'platform-csat'"));
            assertEquals(DEFAULT_TENANT, queryString(dataSource,
                "SELECT `scope_id` FROM `cw_knowledge_gap` WHERE `question_hash` = 'platform-hash'"));
            assertEquals("tenant-a", queryString(dataSource,
                "SELECT `tenant_id` FROM `cw_outbox_message` WHERE `id` = 'tenant-outbox'"));
            assertEquals(DEFAULT_TENANT, queryString(dataSource,
                "SELECT `scope_id` FROM `cw_long_term_memory` WHERE `fact` = 'platform fact'"));
            assertEquals(sha256(DEFAULT_TENANT, "platform fact"), queryString(dataSource,
                "SELECT `scope_hash` FROM `cw_long_term_memory` WHERE `fact` = 'platform fact'"));
            assertEquals("xxplatformyy", queryString(dataSource,
                "SELECT `scope_id` FROM `cw_long_term_memory` WHERE `fact` = 'near-prefix fact'"));
            assertEquals(sha256("xxplatformyy", "near-prefix fact"), queryString(dataSource,
                "SELECT `scope_hash` FROM `cw_long_term_memory` WHERE `fact` = 'near-prefix fact'"));
            assertEquals(DEFAULT_TENANT, queryString(dataSource,
                "SELECT `scope_id` FROM `cw_fact_log` WHERE `fact` = 'platform fact log'"));
            assertEquals("xxplatformyy", queryString(dataSource,
                "SELECT `scope_id` FROM `cw_fact_log` WHERE `fact` = 'near-prefix fact log'"));
            assertEquals(DEFAULT_TENANT, queryString(dataSource,
                "SELECT `scope_id` FROM `cw_semantic_cache` WHERE `question` = 'platform question'"));
            assertEquals("xxplatformyy", queryString(dataSource,
                "SELECT `scope_id` FROM `cw_semantic_cache` WHERE `question` = 'near-prefix question'"));
            assertEquals(1, countHistoryVersion(dataSource, "7"));
            assertEquals(1, countHistoryVersion(dataSource, "8"));
        }
    }

    private String sha256(String scopeId, String fact) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(
            (scopeId + "\n" + fact).getBytes(StandardCharsets.UTF_8)));
    }

    private void migrate(HikariDataSource dataSource, String database) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setDatabase(database);
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
    }

    private void migrateTo(HikariDataSource dataSource, String target) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/customerwork/migration")
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .javaMigrations(new V2__ReconcileLegacySchema(), new V9__AddAuditTimestamps())
            .target(target)
            .load()
            .migrate();
    }

    private void repair(HikariDataSource dataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/customerwork/migration")
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .javaMigrations(new V2__ReconcileLegacySchema(), new V9__AddAuditTimestamps())
            .load()
            .repair();
    }

    private boolean canCreateDatabases(String... databases) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            for (String database : databases) {
                statement.executeUpdate("CREATE DATABASE " + quoted(database)
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            return true;
        } catch (Exception e) {
            for (String database : databases) {
                dropDatabase(database);
            }
            return false;
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + quoted(database));
        } catch (Exception ignored) {
            // 清理失败不覆盖原始断言；随机库名不会影响业务库，残留可按 cw_flyway_* 识别。
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT
            + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", USERNAME, PASSWORD);
    }

    private HikariDataSource dataSource(String database, String poolName) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName(poolName);
        return dataSource;
    }

    private Set<String> tenantTables(HikariDataSource dataSource) throws Exception {
        String sql = "SELECT DISTINCT `table_name` FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND column_name = 'tenant_id' "
            + "AND table_name LIKE 'cw\\_%'";
        Set<String> tables = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    private Set<String> tablesMissingBothAuditTimestamps(HikariDataSource dataSource) throws Exception {
        String sql = "SELECT `table_name` FROM information_schema.tables t "
            + "WHERE t.table_schema = DATABASE() AND t.table_type = 'BASE TABLE' "
            + "AND t.table_name LIKE 'cw\\_%' "
            + "AND NOT EXISTS (SELECT 1 FROM information_schema.columns c "
            + "WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name "
            + "AND c.column_name IN ('created_at', 'created_at_ms')) "
            + "AND NOT EXISTS (SELECT 1 FROM information_schema.columns c "
            + "WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name "
            + "AND c.column_name IN ('updated_at', 'updated_at_ms'))";
        Set<String> tables = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    private int countPlatformRows(HikariDataSource dataSource) throws Exception {
        int count = 0;
        for (String table : tenantTables(dataSource)) {
            count += queryInt(dataSource, "SELECT COUNT(*) FROM " + quoted(table)
                + " WHERE `tenant_id` = '__platform__'");
        }
        return count;
    }

    private int countBusinessTables(HikariDataSource dataSource) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_name LIKE 'cw\\_%'";
        return queryInt(dataSource, sql);
    }

    private int countHistoryRows(HikariDataSource dataSource) throws Exception {
        return queryInt(dataSource, "SELECT COUNT(*) FROM flyway_schema_history");
    }

    private int countHistoryVersion(HikariDataSource dataSource, String version) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private String latestHistoryVersion(HikariDataSource dataSource) throws Exception {
        return queryString(dataSource, "SELECT `version` FROM `flyway_schema_history` "
            + "WHERE `success` = 1 AND `version` IS NOT NULL ORDER BY `installed_rank` DESC LIMIT 1");
    }

    private void assertAuditTimestampColumns(HikariDataSource dataSource) throws Exception {
        for (String table : AUDIT_TIMESTAMP_TABLES) {
            assertAuditTimestampColumn(dataSource, table, "created_at", "记录创建时间", false);
            assertAuditTimestampColumn(dataSource, table, "updated_at", "记录最后修改时间", true);
        }
    }

    private void assertAuditTimestampColumn(HikariDataSource dataSource, String table, String column,
                                            String expectedComment, boolean autoUpdate) throws Exception {
        String sql = "SELECT `data_type`, `datetime_precision`, `is_nullable`, `column_default`, "
            + "`extra`, `column_comment` FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                String label = table + "." + column;
                assertTrue(resultSet.next(), label + " 应存在");
                assertEquals("datetime", resultSet.getString("data_type"), label + " 类型");
                assertEquals(3, resultSet.getInt("datetime_precision"), label + " 毫秒精度");
                assertEquals("NO", resultSet.getString("is_nullable"), label + " 非空约束");
                assertEquals("current_timestamp(3)",
                    normalizeMetadataExpression(resultSet.getString("column_default")), label + " 默认值");
                String extra = normalizeMetadataExpression(resultSet.getString("extra"));
                if (autoUpdate) {
                    assertTrue(extra.contains("onupdatecurrent_timestamp(3)"), label + " 自动更新时间");
                } else {
                    assertFalse(extra.contains("onupdate"), label + " 不应随更新变化");
                }
                assertEquals(expectedComment, resultSet.getString("column_comment"), label + " 注释");
            }
        }
    }

    private String normalizeMetadataExpression(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void verifyUpdatedAtAutoAdvance(HikariDataSource dataSource) throws Exception {
        execute(dataSource, "UPDATE `cw_product` SET `updated_at` = '2000-01-01 00:00:00.000' "
            + "WHERE `product_id` = 'P001'");
        Timestamp createdAtBefore = queryTimestamp(dataSource,
            "SELECT `created_at` FROM `cw_product` WHERE `product_id` = 'P001'");
        Timestamp updatedAtBefore = queryTimestamp(dataSource,
            "SELECT `updated_at` FROM `cw_product` WHERE `product_id` = 'P001'");

        execute(dataSource, "UPDATE `cw_product` SET `stock` = `stock` + 1 WHERE `product_id` = 'P001'");

        assertTrue(queryTimestamp(dataSource,
            "SELECT `updated_at` FROM `cw_product` WHERE `product_id` = 'P001'").after(updatedAtBefore),
            "真实业务字段 UPDATE 后 updated_at 应由数据库推进");
        assertEquals(createdAtBefore, queryTimestamp(dataSource,
            "SELECT `created_at` FROM `cw_product` WHERE `product_id` = 'P001'"),
            "业务字段 UPDATE 不应改写 created_at");
    }

    private boolean columnExists(HikariDataSource dataSource, String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND column_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private int queryInt(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String queryString(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private Timestamp queryTimestamp(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getTimestamp(1);
        }
    }

    private void execute(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
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

    private String quoted(String identifier) {
        if (!identifier.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("illegal database identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
