package com.richard.fyoung.customerwork.data.calllog;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCallLineageMigrationContractTest {

    @Test
    void v12AndCompleteMirror_shouldContainNonSecretLineage() throws Exception {
        String migration;
        try (var input = new ClassPathResource(
            "db/customerwork/migration/V12__agent_call_lineage.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String mirror = Files.readString(repositoryRoot().resolve(
            "mysql/01-agent-scope-customer-work/customer-work-schema.sql"));

        for (String fragment : new String[]{
            "trace_id", "runtime_revision", "runtime_content_hash", "version_binding_json",
            "idx_call_trace_id", "idx_call_runtime_revision"}) {
            assertTrue(migration.contains(fragment), "V12 缺少：" + fragment);
            assertTrue(mirror.contains(fragment), "完整镜像缺少：" + fragment);
        }
        String lower = migration.toLowerCase();
        assertFalse(lower.contains("api_key"));
        assertFalse(lower.contains("secret"));
        assertFalse(lower.contains("header"));
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }
}
