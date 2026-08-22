package com.richard.fyoung.customeradmin.slo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloMigrationContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/migration/V73__enterprise_slo_error_budget.sql");
    private static final Path MIRROR = Path.of("../mysql/02-customer-admin/73-V73__enterprise_slo_error_budget.sql");
    private static final Path MINIMUM_SAMPLES_FLYWAY =
        Path.of("src/main/resources/db/migration/V79__slo_minimum_sample_count.sql");
    private static final Path MINIMUM_SAMPLES_MIRROR =
        Path.of("../mysql/02-customer-admin/79-V79__slo_minimum_sample_count.sql");

    @Test
    void migration_shouldKeepFlywayAndManualMirrorByteEqual() throws Exception {
        assertEquals(Files.readString(FLYWAY), Files.readString(MIRROR));
        assertEquals(Files.readString(MINIMUM_SAMPLES_FLYWAY),
            Files.readString(MINIMUM_SAMPLES_MIRROR));
    }

    @Test
    void migration_shouldDefineTenantScopedPolicyAndIdempotentAlertFact() throws Exception {
        String sql = Files.readString(FLYWAY);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_slo_policy`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_slo_alert`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_slo_alert_fact` (`tenant_id`, `policy_id`, `window_end_minute`, `alert_type`)"));
        assertTrue(sql.contains("'slo:evaluate'"));
    }

    @Test
    void minimumSamplesMigration_shouldBackfillExistingPoliciesWithConservativeDefault()
        throws Exception {
        String sql = Files.readString(MINIMUM_SAMPLES_FLYWAY);
        assertTrue(sql.contains("ADD COLUMN `minimum_sample_count` INT NOT NULL DEFAULT 100"));
        assertTrue(sql.contains("CHECK (`minimum_sample_count` > 0)"));
        assertTrue(sql.contains("ALTER TABLE `ai_slo_policy`"));
    }
}
