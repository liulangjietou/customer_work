package com.richard.fyoung.customerwork.data.calllog;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineExperimentAttributionMigrationContractTest {

    @Test
    void v13ShouldAddPrivacySafeExposureColumns() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/customerwork/migration/V13__online_experiment_attribution.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("`experiment_id`"));
        assertTrue(sql.contains("`experiment_revision`"));
        assertTrue(sql.contains("`experiment_arm`"));
        assertTrue(sql.contains("`experiment_deployment_id`"));
        assertTrue(sql.contains("`experiment_bucket`"));
        assertTrue(sql.contains("`idx_call_experiment_arm`"));
        assertTrue(!sql.contains("assignment_salt"));
        assertTrue(!sql.contains("user_id"));
    }
}
