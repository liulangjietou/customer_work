package com.richard.fyoung.customerwork.data.calllog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 客服端模型结算迁移、手工升级脚本与全量 schema 的一致性契约。 */
class ModelCallCostMigrationContractTest {

    @Test
    void migrationManualScriptAndSchema_shouldShareCostSettlementContract() throws Exception {
        String migration = Files.readString(Path.of(
            "src/main/resources/db/customerwork/migration/V20__model_call_cost_settlement.sql"));
        String manual = Files.readString(Path.of(
            "../mysql/01-agent-scope-customer-work/customer-work-model-cost-settlement-alter.sql"));
        String schema = Files.readString(Path.of(
            "../mysql/01-agent-scope-customer-work/customer-work-schema.sql"));

        assertEquals(migration.replaceFirst(
                "-- 每个 MODEL 分段按冻结价目结算金额，并把完整性汇总到调用主记录。",
                "-- 已有 customer_work 库升级：按冻结价目结算每个模型调用，并汇总调用成本。"),
            manual);
        for (String column : new String[]{"cost_amount", "cost_currency", "cost_status",
            "model_cost_amount", "model_cost_currency", "model_cost_status",
            "model_segment_count", "settled_cost_segment_count", "unsettled_cost_segment_count"}) {
            assertTrue(migration.contains("column_name = '" + column + "'"));
            assertTrue(schema.contains("`" + column + "`"));
        }
        assertTrue(migration.contains("DECIMAL(30,14)"));
        assertTrue(migration.contains("MULTI_CURRENCY"));
    }
}
