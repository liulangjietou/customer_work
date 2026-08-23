package com.richard.fyoung.customeradmin.workspace.callstats;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCallLineageMigrationContractTest {

    @Test
    void v70_shouldMatchDbaMirrorAndContainNoCredentials() throws Exception {
        Path module = Path.of("src/main/resources/db/migration/V70__agent_call_lineage.sql");
        Path mirror = Path.of("../mysql/02-customer-admin/70-V70__agent_call_lineage.sql");
        String sql = Files.readString(module);

        assertEquals(sql, Files.readString(mirror));
        assertTrue(sql.contains("trace_id"));
        assertTrue(sql.contains("runtime_revision"));
        assertTrue(sql.contains("runtime_content_hash"));
        assertTrue(sql.contains("version_binding_json"));
        String lower = sql.toLowerCase();
        assertFalse(lower.contains("api_key"));
        assertFalse(lower.contains("secret"));
        assertFalse(lower.contains("header"));
    }
}
