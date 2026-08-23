package com.richard.fyoung.customeradmin.aiconfig.experiment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineExperimentAttributionMigrationContractTest {

    @Test
    void v74ShouldMirrorAdminSqlAndAddQueryableExposureIdentity() throws Exception {
        Path module = Path.of("src/main/resources/db/migration/V74__online_experiment_attribution.sql");
        Path mirror = Path.of("../mysql/02-customer-admin/74-V74__online_experiment_attribution.sql");
        String sql = Files.readString(module, StandardCharsets.UTF_8);

        assertEquals(sql, Files.readString(mirror, StandardCharsets.UTF_8));
        assertTrue(sql.contains("`experiment_id`"));
        assertTrue(sql.contains("`experiment_revision`"));
        assertTrue(sql.contains("`experiment_arm`"));
        assertTrue(sql.contains("`experiment_deployment_id`"));
        assertTrue(sql.contains("`experiment_bucket`"));
        assertTrue(sql.contains("`idx_call_experiment_arm`"));
        assertTrue(sql.toLowerCase().contains("alter table `cw_agent_call_log`"));
    }
}
