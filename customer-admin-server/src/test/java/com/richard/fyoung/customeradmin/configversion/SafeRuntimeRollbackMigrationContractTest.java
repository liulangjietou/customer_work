package com.richard.fyoung.customeradmin.configversion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeRuntimeRollbackMigrationContractTest {

    @Test
    void v77_shouldPersistSafeIntentAuditAndMatchDbaMirror() throws Exception {
        Path module = Path.of("src/main/resources/db/migration/V77__safe_runtime_config_rollback.sql");
        Path mirror = Path.of("../mysql/02-customer-admin/77-V77__safe_runtime_config_rollback.sql");
        String sql = Files.readString(module, StandardCharsets.UTF_8);

        assertEquals(sql, Files.readString(mirror, StandardCharsets.UTF_8));
        assertTrue(sql.contains("`operation_id`"));
        assertTrue(sql.contains("`publish_intent`"));
        assertTrue(sql.contains("`source_config_version_id`"));
        assertTrue(sql.contains("`source_content_hash`"));
        assertTrue(sql.contains("`rollback_patch_json`"));
        assertTrue(sql.contains("'SAFE_ROLLBACK', 'SAFE_GRAY'"));
        assertTrue(sql.contains("`chk_runtime_publish_safe_intent`"));
        assertFalse(sql.contains("api_key_cipher"));
        assertFalse(sql.contains("assignment_salt"));
    }

    @Test
    void publisher_shouldExposeNoHistoricalJsonDirectPublishEntry() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/richard/fyoung/customeradmin/aiconfig/channel/publish/"
                + "CustomerWorkConfigPublisher.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("publishRollbackToCurrentTenant"));
        assertFalse(source.contains("publishToDataId"));
        assertFalse(source.contains("public String publishJson("));
    }
}
