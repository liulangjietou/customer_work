-- KnowledgeGap/badcase 共用的治理状态机；原始信号仍留在客服库。
CREATE TABLE IF NOT EXISTS `ai_agent_improvement_case` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` VARCHAR(64) NOT NULL,
    `source_type` VARCHAR(32) NOT NULL COMMENT 'KNOWLEDGE_GAP/BADCASE',
    `source_key` VARCHAR(128) NOT NULL COMMENT 'questionHash或badcaseId',
    `signal_hash` CHAR(64) NOT NULL COMMENT '同类问题复发观测键',
    `source_signal_count` BIGINT NOT NULL DEFAULT 0 COMMENT '认领时累计信号数',
    `owner_id` VARCHAR(64) NOT NULL,
    `sla_due_at_ms` BIGINT NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `agent_id` BIGINT DEFAULT NULL,
    `agent_code` VARCHAR(128) DEFAULT NULL,
    `artifact_type` VARCHAR(32) DEFAULT NULL,
    `artifact_version` CHAR(64) DEFAULT NULL COMMENT '精确候选版本指纹',
    `candidate_versions_json` JSON DEFAULT NULL COMMENT '非密钥九维版本绑定',
    `eval_type` VARCHAR(16) DEFAULT NULL,
    `eval_case_id` VARCHAR(128) DEFAULT NULL,
    `eval_run_id` VARCHAR(64) DEFAULT NULL,
    `reevaluation_status` VARCHAR(24) NOT NULL DEFAULT 'NOT_RUN',
    `reevaluation_verdict` VARCHAR(24) DEFAULT NULL,
    `reevaluation_error` VARCHAR(1000) DEFAULT NULL,
    `publish_task_id` VARCHAR(64) DEFAULT NULL,
    `publish_revision` VARCHAR(64) DEFAULT NULL,
    `publish_status` VARCHAR(24) DEFAULT NULL,
    `published_at_ms` BIGINT DEFAULT NULL COMMENT 'Worker观测到全目标APPLIED的时间',
    `baseline_signal_count` BIGINT DEFAULT NULL,
    `observation_started_at_ms` BIGINT DEFAULT NULL,
    `observation_ends_at_ms` BIGINT DEFAULT NULL,
    `min_exposure_calls` INT DEFAULT NULL,
    `max_recurrence_signals` INT DEFAULT NULL,
    `observed_calls` BIGINT NOT NULL DEFAULT 0,
    `observed_signals` BIGINT NOT NULL DEFAULT 0,
    `effect_status` VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    `last_observed_at_ms` BIGINT DEFAULT NULL,
    `next_action_at_ms` BIGINT NOT NULL,
    `lease_owner` VARCHAR(160) DEFAULT NULL,
    `lease_until_ms` BIGINT NOT NULL DEFAULT 0,
    `automation_failures` INT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `created_at_ms` BIGINT NOT NULL,
    `updated_at_ms` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_improvement_source` (`tenant_id`, `source_type`, `source_key`),
    KEY `idx_improvement_owner_sla` (`tenant_id`, `owner_id`, `status`, `sla_due_at_ms`),
    KEY `idx_improvement_due` (`status`, `next_action_at_ms`, `lease_until_ms`),
    KEY `idx_improvement_publish` (`tenant_id`, `publish_task_id`),
    CONSTRAINT `chk_improvement_source_type` CHECK (`source_type` IN ('KNOWLEDGE_GAP', 'BADCASE')),
    CONSTRAINT `chk_improvement_status` CHECK (`status` IN (
        'OWNED', 'READY_FOR_REEVALUATION', 'REEVALUATING', 'REEVALUATION_FAILED',
        'READY_TO_PUBLISH', 'PUBLISHING', 'PUBLISH_FAILED', 'OBSERVING',
        'VERIFIED', 'INEFFECTIVE', 'INCONCLUSIVE', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='智能体问题从认领到线上效果验证的治理闭环';

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT p.id, '管理改进闭环', 'improvement:manage', 2, 20
FROM `sys_permission` p
WHERE p.perm_code = 'ops'
  AND NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'improvement:manage');

-- 既有 badcase 筛选人和知识补充人继承闭环管理权；复评/发布仍分别要求 eval:run / agent:edit。
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `tenant_id`)
SELECT DISTINCT rp.role_id, target.id, rp.tenant_id
FROM `sys_role_permission` rp
JOIN `sys_permission` source_permission
  ON source_permission.id = rp.permission_id
 AND source_permission.perm_code IN ('badcase:adopt', 'knowledge-gap:fill')
JOIN `sys_permission` target ON target.perm_code = 'improvement:manage'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_role_permission` existing
    WHERE existing.role_id = rp.role_id
      AND existing.permission_id = target.id
      AND existing.tenant_id = rp.tenant_id
);
