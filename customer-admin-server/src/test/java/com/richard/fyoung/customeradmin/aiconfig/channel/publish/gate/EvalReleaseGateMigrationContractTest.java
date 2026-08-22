package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalReleaseGateMigrationContractTest {

    @Test
    void v68ShouldExtendReliableTaskAndMatchDbaMirror() throws Exception {
        String sql;
        try (var input = new ClassPathResource("db/migration/V68__eval_release_gate.sql")
            .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String mirror = Files.readString(repositoryRoot().resolve(
            "mysql/02-customer-admin/68-V68__eval_release_gate.sql"));

        assertEquals(sql, mirror, "Flyway 迁移与 DBA 镜像必须逐字一致");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_eval_release_gate_policy`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_eval_release_gate_override`"));
        assertTrue(sql.contains("ALTER TABLE `ai_runtime_publish_task`"),
            "门禁必须扩展既有可靠发布任务");
        assertTrue(sql.contains("uk_eval_gate_policy_tenant_type"));
        assertTrue(sql.contains("uk_eval_gate_override_task"));
        assertTrue(sql.contains("'eval:gate-policy-edit'"));
        assertTrue(sql.contains("'eval:gate-override'"));
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS `ai_eval_publish_task`"),
            "禁止另造发布状态机");
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }
}
