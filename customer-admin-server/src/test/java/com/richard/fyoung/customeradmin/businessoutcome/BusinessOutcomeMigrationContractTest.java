package com.richard.fyoung.customeradmin.businessoutcome;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessOutcomeMigrationContractTest {

    private static final Path FLYWAY = Path.of(
        "src/main/resources/db/migration/V75__business_outcome_cost_view.sql");
    private static final Path MIRROR = Path.of(
        "../mysql/02-customer-admin/75-V75__business_outcome_cost_view.sql");

    @Test
    void migration_shouldKeepFlywayAndManualMirrorByteEqual() throws Exception {
        assertEquals(Files.readString(FLYWAY), Files.readString(MIRROR));
    }

    @Test
    void migration_shouldOnlyAddIdempotentReadPermission() throws Exception {
        String sql = Files.readString(FLYWAY);
        assertTrue(sql.contains("'business-outcome:view'"));
        assertTrue(sql.contains("'/ops/business-outcome'"));
        assertTrue(sql.contains("NOT EXISTS"));
        assertFalse(sql.contains("CREATE TABLE"));
    }
}
