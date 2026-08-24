package com.richard.fyoung.customerwork.capability.badcase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** badcase 复发键迁移、手工升级脚本与全量 schema 的一致性契约。 */
class BadcaseRecurrenceSignalMigrationContractTest {

    @Test
    void migrationManualScriptAndSchema_shouldShareSignalHashContract() throws Exception {
        String migration = Files.readString(Path.of(
            "src/main/resources/db/customerwork/migration/V21__badcase_recurrence_signal.sql"));
        String manual = Files.readString(Path.of(
            "../mysql/01-agent-scope-customer-work/customer-work-badcase-recurrence-signal-alter.sql"));
        String schema = Files.readString(Path.of(
            "../mysql/01-agent-scope-customer-work/customer-work-schema.sql"));

        assertEquals(migration.replaceFirst(
                "-- 为 badcase 固化归一化问题哈希，使上线效果观察无需扫描或复制聊天正文。",
                "-- 已有 customer_work 库升级：为 badcase 固化归一化问题哈希，支持上线效果观察。"),
            manual);
        assertTrue(migration.contains("LEFT(TRIM(`user_input`), 500)"));
        assertTrue(migration.contains("SHA2("));
        assertTrue(migration.contains("idx_badcase_signal"));
        assertTrue(schema.contains("`signal_hash`"));
        assertTrue(schema.contains("INDEX `idx_badcase_signal` (`tenant_id`, `signal_hash`, `created_at_ms`)"));
    }
}
