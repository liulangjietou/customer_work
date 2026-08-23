package com.richard.fyoung.customeradmin.aiconfig.model;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V67 模型资产、SecretRef、健康事件、存量回填与权限迁移契约。 */
class ModelOpsMigrationContractTest {

    @Test
    void v67_shouldBeAdditiveBackfillLegacyDeploymentsAndMatchDbaMirror() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
            "db/migration/V67__enterprise_modelops.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String mirror = Files.readString(repositoryRoot().resolve(
            "mysql/02-customer-admin/67-V67__enterprise_modelops.sql"));

        assertEquals(sql, mirror, "Flyway 迁移与 DBA 镜像必须逐字一致");
        assertTrue(sql.startsWith("SET NAMES utf8mb4;\n"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_model_asset`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_secret_ref`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_secret_material`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_model_health_snapshot`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_model_health_event`"));
        assertTrue(sql.contains("ADD COLUMN `asset_id`"));
        assertTrue(sql.contains("ADD COLUMN `secret_ref_id`"));
        assertTrue(sql.contains("CONCAT('legacy-asset-', `id`)"));
        assertTrue(sql.contains("`mc`.`api_key`, 'admin-aes-gcm'"),
            "存量凭据必须按密文原样回填，不得在 SQL 中解密");
        assertTrue(sql.contains("SET `mc`.`secret_ref_id` = `ref`.`id`"));
        assertTrue(sql.contains("'MIGRATION', 'CONNECTIVITY'"));
        assertTrue(sql.contains("'model:health-test'"));
        assertFalse(sql.toUpperCase().contains("DROP COLUMN"),
            "旧 agent.model_id 仍指向 ai_model_config.id，首切片禁止破坏式删列");
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }
}
