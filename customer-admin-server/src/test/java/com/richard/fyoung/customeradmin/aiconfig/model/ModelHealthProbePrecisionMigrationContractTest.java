package com.richard.fyoung.customeradmin.aiconfig.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V78 健康 probe 并发排序所需的最小 DDL 契约。 */
class ModelHealthProbePrecisionMigrationContractTest {

    @Test
    void v78_shouldUseMicrosecondProbeOrderingAndMatchDbaMirror() throws Exception {
        Path module = Path.of("src/main/resources/db/migration/V78__model_health_probe_precision.sql");
        Path mirror = Path.of("../mysql/02-customer-admin/78-V78__model_health_probe_precision.sql");
        String sql = Files.readString(module);

        assertEquals(sql, Files.readString(mirror));
        assertTrue(sql.contains("`last_probe_at` DATETIME(6)"));
        assertTrue(sql.contains("`occurred_at` DATETIME(6)"));
    }
}
