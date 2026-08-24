package com.richard.fyoung.customeradmin.improvement;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admin V96 与 DBA 镜像的改进闭环状态、租约和权限契约。 */
class AgentImprovementMigrationContractTest {

    @Test
    void migrationAndDbaMirror_shouldBeIdenticalAndExposeClosedLoopFacts() throws Exception {
        String migration = Files.readString(Path.of(
            "src/main/resources/db/migration/V96__agent_improvement_closed_loop.sql"));
        String mirror = Files.readString(Path.of(
            "../mysql/02-customer-admin/96-V96__agent_improvement_closed_loop.sql"));

        assertEquals(migration, mirror);
        for (String fact : new String[]{"ai_agent_improvement_case", "owner_id", "sla_due_at_ms",
            "artifact_version", "candidate_versions_json", "reevaluation_status", "publish_task_id",
            "publish_revision", "observation_ends_at_ms", "lease_owner", "next_action_at_ms",
            "uk_improvement_source", "idx_improvement_due", "improvement:manage"}) {
            assertTrue(migration.contains(fact), fact);
        }
        assertTrue(migration.contains("'VERIFIED', 'INEFFECTIVE', 'INCONCLUSIVE'"));
        assertTrue(migration.contains("'badcase:adopt', 'knowledge-gap:fill'"));
        assertTrue(migration.contains("existing.tenant_id = rp.tenant_id"));
    }
}
