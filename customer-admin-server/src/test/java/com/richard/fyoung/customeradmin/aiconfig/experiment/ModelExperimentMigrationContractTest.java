package com.richard.fyoung.customeradmin.aiconfig.experiment;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelExperimentMigrationContractTest {

    @Test
    void v72_shouldMirrorControlPlaneContract_withoutPersistingCredentials() throws Exception {
        Path module = Path.of("src/main/resources/db/migration/V72__model_online_experiment.sql");
        Path mirror = Path.of("../mysql/02-customer-admin/72-V72__model_online_experiment.sql");
        String sql = Files.readString(module);

        assertEquals(sql, Files.readString(mirror));
        assertTrue(sql.contains("uk_model_experiment_one_running_agent"));
        assertTrue(sql.contains("GENERATED ALWAYS AS"));
        assertTrue(sql.contains("DRAFT/RUNNING/STOPPED/COMPLETED"));
        assertTrue(sql.contains("START/STOP/AUTO_STOP/EXPIRED"));
        assertTrue(sql.contains("model-experiment:start"));
        assertTrue(sql.contains("model-experiment:stop"));
        assertFalse(sql.contains("api_key"));
        assertFalse(sql.contains("cipher_text"));
        assertFalse(sql.contains("secret_ref_id"));
    }
}
