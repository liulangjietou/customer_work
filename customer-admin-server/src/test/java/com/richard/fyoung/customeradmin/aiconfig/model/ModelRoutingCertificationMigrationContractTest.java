package com.richard.fyoung.customeradmin.aiconfig.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRoutingCertificationMigrationContractTest {

    @Test
    void v69_shouldKeepMirrorIdenticalAndNeverPersistRouteSecrets() throws Exception {
        Path module = Path.of("src/main/resources/db/migration/V69__model_routing_certification.sql");
        Path mirror = Path.of("../mysql/02-customer-admin/69-V69__model_routing_certification.sql");
        String sql = Files.readString(module);

        assertEquals(sql, Files.readString(mirror));
        assertTrue(sql.contains("ai_model_route_policy_version"));
        assertTrue(sql.contains("ai_model_certification_run"));
        assertTrue(sql.contains("certification_required"));
        assertTrue(sql.contains("model:certify"));
        String routeRule = sql.substring(sql.indexOf("CREATE TABLE IF NOT EXISTS `ai_model_route_rule`"),
            sql.indexOf("CREATE TABLE IF NOT EXISTS `ai_model_certification_run`"));
        assertTrue(routeRule.contains("deployment_id"));
        assertFalse(routeRule.contains("api_key"));
        assertFalse(routeRule.contains("secret_ref_id"));
        assertFalse(routeRule.contains("cipher_text"));
    }
}
