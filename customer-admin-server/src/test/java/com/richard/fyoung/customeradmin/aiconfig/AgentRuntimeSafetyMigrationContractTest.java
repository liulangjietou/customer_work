package com.richard.fyoung.customeradmin.aiconfig;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0 智能体运行时安全迁移与管理员手工 SQL 镜像契约。 */
class AgentRuntimeSafetyMigrationContractTest {

    @Test
    void flywayMigrationsShouldMatchAdminSqlMirrors() throws Exception {
        assertMirror(81, "agent_runtime_revision");
        assertMirror(82, "mcp_subject_authorization");
        assertMirror(83, "agent_memory_subject_scope");
        assertMirror(84, "scheduled_task_global_claim");
        assertMirror(85, "model_call_attribution");
        assertMirror(86, "agent_cross_pod_coordination");
        assertMirror(87, "agent_task_lease_recovery");
        assertMirror(88, "wechat_callback_safe_mode");
        assertMirror(89, "agent_governed_delivery");
        assertMirror(90, "model_health_routing_overlay");
        assertMirror(91, "knowledge_assets_immutable_versions");
    }

    @Test
    void alterMigrationsShouldContainPreflightAndRepairSafeChecks() throws Exception {
        for (int version : new int[]{81, 82, 83}) {
            String sql = readFlyway(version, switch (version) {
                case 81 -> "agent_runtime_revision";
                case 82 -> "mcp_subject_authorization";
                default -> "agent_memory_subject_scope";
            });
            assertTrue(sql.contains("information_schema.tables"));
            assertTrue(sql.contains("information_schema.columns"));
            assertTrue(sql.contains("PREPARE"));
        }
        String claim = readFlyway(84, "scheduled_task_global_claim");
        assertTrue(claim.contains("CREATE TABLE IF NOT EXISTS `ai_scheduled_task_claim`"));
        assertTrue(claim.contains("PRIMARY KEY (`tenant_id`, `task_id`, `fire_time`)"));
        String attribution = readFlyway(85, "model_call_attribution");
        assertTrue(attribution.contains("column_name = 'pricing_status'"));
        assertTrue(attribution.contains("DEFAULT ''UNPRICED''"));
        String coordination = readFlyway(86, "agent_cross_pod_coordination");
        assertTrue(coordination.contains("CREATE TABLE IF NOT EXISTS `ai_plan_confirmation`"));
        assertTrue(coordination.contains("column_name = 'version'"));
        String leaseRecovery = readFlyway(87, "agent_task_lease_recovery");
        assertTrue(leaseRecovery.contains("information_schema.tables"));
        assertTrue(leaseRecovery.contains("column_name = 'owner_id'"));
        assertTrue(leaseRecovery.contains("idx_task_lease_recovery"));
        assertTrue(leaseRecovery.contains("`replayable` = 0"));
        String wechatSafeMode = readFlyway(88, "wechat_callback_safe_mode");
        assertTrue(wechatSafeMode.contains("information_schema.tables"));
        assertTrue(wechatSafeMode.contains("column_name = 'callback_mode'"));
        assertTrue(wechatSafeMode.contains("column_name = 'encoding_aes_key_cipher'"));
        assertTrue(wechatSafeMode.contains("DEFAULT ''plaintext''"));
        String governedDelivery = readFlyway(89, "agent_governed_delivery");
        assertTrue(governedDelivery.contains("column_name = 'ack_targets_json'"));
        assertTrue(governedDelivery.contains("column_name = 'secret_ref_id'"));
        assertTrue(governedDelivery.contains("CREATE TABLE IF NOT EXISTS `ai_governed_change_request`"));
        assertTrue(governedDelivery.contains("CREATE TABLE IF NOT EXISTS `ai_governance_audit_event`"));
        assertTrue(governedDelivery.contains("column_name = 'retention_until'"));
        String healthRouting = readFlyway(90, "model_health_routing_overlay");
        assertTrue(healthRouting.contains("column_name = 'consecutive_successes'"));
        assertTrue(healthRouting.contains("column_name = 'cooldown_until'"));
        assertTrue(healthRouting.contains("column_name = 'override_mode'"));
        assertTrue(healthRouting.contains("idx_model_health_override_expiry"));
        assertTrue(healthRouting.contains("column_name = 'event_type'"));
        assertTrue(healthRouting.contains("'model:health-override'"));
        String knowledgeOps = readFlyway(91, "knowledge_assets_immutable_versions");
        assertTrue(knowledgeOps.contains("CREATE TABLE IF NOT EXISTS `ai_skill_version`"));
        assertTrue(knowledgeOps.contains("CREATE TABLE IF NOT EXISTS `ai_knowledge_base_version`"));
        assertTrue(knowledgeOps.contains("CREATE TABLE IF NOT EXISTS `ai_knowledge_source`"));
        assertTrue(knowledgeOps.contains("CREATE TABLE IF NOT EXISTS `ai_knowledge_sync_run`"));
        assertTrue(knowledgeOps.contains("column_name = 'skill_version_id'"));
        assertTrue(knowledgeOps.contains("column_name = 'knowledge_base_version_id'"));
        assertTrue(knowledgeOps.contains("`skill_version_id` BIGINT NOT NULL"));
        assertTrue(knowledgeOps.contains("`knowledge_base_version_id` BIGINT NOT NULL"));
        assertTrue(knowledgeOps.contains("'knowledge-base:source-sync'"));
    }

    private void assertMirror(int version, String slug) throws Exception {
        String flyway = readFlyway(version, slug);
        Path mirror = Path.of("../mysql/02-customer-admin/" + version + "-V" + version + "__" + slug + ".sql");
        assertEquals(flyway, Files.readString(mirror));
    }

    private String readFlyway(int version, String slug) throws Exception {
        return Files.readString(Path.of("src/main/resources/db/migration/V" + version + "__" + slug + ".sql"));
    }
}
