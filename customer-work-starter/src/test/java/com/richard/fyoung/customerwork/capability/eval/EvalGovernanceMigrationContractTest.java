package com.richard.fyoung.customerwork.capability.eval;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalGovernanceMigrationContractTest {

    @Test
    void v11AndCompleteMirrorShouldContainImmutableDatasetAndRunBindings() throws Exception {
        String migration;
        try (var input = new ClassPathResource(
            "db/customerwork/migration/V11__eval_dataset_version_and_artifact_binding.sql")
            .getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String mirror = Files.readString(repositoryRoot().resolve(
            "mysql/01-agent-scope-customer-work/customer-work-schema.sql"));

        for (String fragment : new String[]{
            "cw_eval_dataset_version", "uk_eval_dataset_content", "dataset_version_id",
            "dataset_fingerprint", "version_binding_json", "idx_eval_run_dataset_version"}) {
            assertTrue(migration.contains(fragment), "V11 缺少：" + fragment);
            assertTrue(mirror.contains(fragment), "完整镜像缺少：" + fragment);
        }
        assertFalse(migration.toUpperCase().contains("DROP TABLE"));
        assertFalse(migration.toUpperCase().contains("DROP COLUMN"));
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }
}
