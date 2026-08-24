package com.richard.fyoung.customeradmin.billing;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admin V95 与 DBA 镜像的模型结算、日账单和串行锁契约。 */
class ModelCostSettlementMigrationContractTest {

    @Test
    void migrationAndDbaMirror_shouldBeIdenticalAndExposeReconciliationFacts() throws Exception {
        String migration = Files.readString(Path.of(
            "src/main/resources/db/migration/V95__model_cost_settlement_and_billing_reconciliation.sql"));
        String mirror = Files.readString(Path.of(
            "../mysql/02-customer-admin/95-V95__model_cost_settlement_and_billing_reconciliation.sql"));

        assertEquals(migration, mirror);
        for (String fact : new String[]{"cost_amount", "model_cost_amount",
            "settled_segment_count", "unsettled_segment_count", "source_max_call_log_id",
            "cw_usage_aggregation_lock", "DECIMAL(30,14)", "idx_agent_call_cost_window"}) {
            assertTrue(migration.contains(fact), fact);
        }
        assertTrue(migration.contains("DROP INDEX `uk_tenant_usage_daily`"));
        assertTrue(migration.contains("`provider`, `model_name`, `currency`"));
    }
}
