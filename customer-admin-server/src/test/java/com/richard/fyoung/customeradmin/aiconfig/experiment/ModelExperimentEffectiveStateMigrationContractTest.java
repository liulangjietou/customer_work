package com.richard.fyoung.customeradmin.aiconfig.experiment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelExperimentEffectiveStateMigrationContractTest {

    @Test
    void v76_shouldMirrorTaskReferencesAndImmutablePublishIntent() throws Exception {
        Path module = Path.of(
            "src/main/resources/db/migration/V76__model_experiment_effective_state.sql");
        Path mirror = Path.of(
            "../mysql/02-customer-admin/76-V76__model_experiment_effective_state.sql");
        String sql = Files.readString(module, StandardCharsets.UTF_8);

        assertEquals(sql, Files.readString(mirror, StandardCharsets.UTF_8));
        assertTrue(sql.contains("`activation_task_id`"));
        assertTrue(sql.contains("`deactivation_task_id`"));
        assertTrue(sql.contains("`experiment_id`"));
        assertTrue(sql.contains("`experiment_publish_action`"));
        assertTrue(sql.contains("'ACTIVATE', 'DEACTIVATE'"));
        assertTrue(sql.contains("`chk_runtime_publish_experiment_intent`"));
    }
}
