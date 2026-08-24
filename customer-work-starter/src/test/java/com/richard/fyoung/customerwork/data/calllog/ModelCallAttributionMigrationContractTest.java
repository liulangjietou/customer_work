package com.richard.fyoung.customerwork.data.calllog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Customer 模型调用归因迁移、全量 schema 与手工升级脚本契约。 */
class ModelCallAttributionMigrationContractTest {

    @Test
    void migrationAndManualSchemaShouldContainFrozenAttributionColumns() throws Exception {
        String migration = Files.readString(Path.of(
            "src/main/resources/db/customerwork/migration/V16__model_call_attribution.sql"));
        String manual = Files.readString(Path.of(
            "../mysql/01-agent-scope-customer-work/customer-work-model-attribution-alter.sql"));
        String schema = Files.readString(Path.of(
            "../mysql/01-agent-scope-customer-work/customer-work-schema.sql"));

        assertEquals(migration.replaceFirst("-- 每个 MODEL 分段冻结实际供应商、部署、模型与调用时价目；缺价显式 UNPRICED。",
                "-- 已有 customer_work 库升级：冻结每个真实模型调用的部署与价目。"),
            manual);
        for (String column : new String[]{"provider", "deployment_id", "model_name", "price_id",
            "currency", "input_unit_price", "output_unit_price", "cached_unit_price", "pricing_status"}) {
            assertTrue(migration.contains("column_name = '" + column + "'"));
            assertTrue(schema.contains("`" + column + "`"));
        }
    }
}
