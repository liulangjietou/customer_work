package com.richard.fyoung.customeradmin.slo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloAutomationMigrationContractTest {

    private static final Path FLYWAY =
        Path.of("src/main/resources/db/migration/V94__slo_automation_alert_lifecycle.sql");
    private static final Path MIRROR =
        Path.of("../mysql/02-customer-admin/94-V94__slo_automation_alert_lifecycle.sql");
    private static final Path POLICY_MAPPER = Path.of("src/main/resources/mapper/SloPolicyMapper.xml");
    private static final Path NOTIFICATION_MAPPER =
        Path.of("src/main/resources/mapper/SloNotificationTaskMapper.xml");

    @Test
    void migration_shouldKeepManualMirrorByteEqualAndDefineLifecycleFacts() throws Exception {
        String sql = Files.readString(FLYWAY);
        assertEquals(sql, Files.readString(MIRROR));
        assertTrue(sql.contains("`evaluation_lease_owner`"));
        assertTrue(sql.contains("`uk_slo_alert_active`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_slo_alert_event`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_slo_notification_task`"));
        assertTrue(sql.contains("'slo:ack'"));
    }

    @Test
    void workerSql_shouldLeaseEvaluationAndLockNotificationBeforeSideEffects() throws Exception {
        String policySql = Files.readString(POLICY_MAPPER);
        String notificationSql = Files.readString(NOTIFICATION_MAPPER);
        assertTrue(policySql.contains("evaluation_lease_until_ms &lt; #{nowMs}"));
        assertTrue(policySql.contains("evaluation_lease_owner = #{owner}"));
        assertTrue(notificationSql.contains("FOR UPDATE"));
        assertTrue(notificationSql.contains("lease_owner = #{owner}"));
        assertTrue(notificationSql.contains("p.perm_code = 'slo:view'"));
    }
}
