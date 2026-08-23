package com.richard.fyoung.customeradmin.billing;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V66 金额告警表、幂等键、权限语义与 DBA 镜像契约。 */
class CostBudgetMigrationContractTest {

    @Test
    void v66_shouldDefineBusinessUniqueKeyAndMatchDbaMirror() throws Exception {
        String sql;
        try (var input = new ClassPathResource("db/migration/V66__cost_budget_alerts.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path repositoryRoot = Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
        String mirrorSql = Files.readString(repositoryRoot.resolve(
            "mysql/02-customer-admin/66-V66__cost_budget_alerts.sql"));

        assertTrue(sql.startsWith("SET NAMES utf8mb4;\n"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_cost_alert`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_cost_alert_business` "
            + "(`tenant_id`, `period`, `period_key`, `alert_type`)"));
        assertTrue(sql.contains("(248, 224, '手工归集', 'billing:aggregate', 2, 4)"));
        assertEquals(sql, mirrorSql, "Flyway 迁移与 DBA 镜像必须逐字一致");
    }

    @Test
    void mapper_shouldUseInsertIgnoreForConcurrentIdempotency() throws Exception {
        String xml;
        try (var input = new ClassPathResource("mapper/CostAlertMapper.xml").getInputStream()) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("INSERT IGNORE INTO ai_cost_alert"));
        assertTrue(xml.contains("WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'OPEN'"));
        assertTrue(xml.contains("WHERE u.tenant_id = #{tenantId}"));
        assertTrue(xml.contains("AND ur.tenant_id = #{tenantId}"));
        assertTrue(xml.contains("AND r.tenant_id = #{tenantId}"));
        assertTrue(xml.contains("AND rp.tenant_id = #{tenantId}"));
    }
}
