package com.richard.fyoung.customeradmin.aiconfig.experiment;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V92 与 DBA 镜像必须同时交付数据集权限、实验制品绑定和双臂评测事实。 */
class EvalDatasetGovernanceMigrationContractTest {

    @Test
    void flywayAndDbaMirrorShouldStayIdenticalAndContainOfflineGate() throws Exception {
        Path root = repositoryRoot();
        String migration = Files.readString(root.resolve(
            "customer-admin-server/src/main/resources/db/migration/V92__eval_dataset_governance.sql"));
        String mirror = Files.readString(root.resolve(
            "mysql/02-customer-admin/92-V92__eval_dataset_governance.sql"));

        assertEquals(migration, mirror);
        for (String fragment : new String[]{"dataset_release_id", "dataset_content_hash",
            "judge_endpoint_revision", "offline_eval_status", "ai_model_experiment_arm_eval",
            "CONTROL", "TREATMENT", "eval:dataset-edit", "eval:dataset-review"}) {
            assertTrue(migration.contains(fragment), "V92 缺少：" + fragment);
        }
        assertTrue(migration.contains("information_schema.columns"), "ALTER 必须支持 repair 后重试");
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }
}
