SET NAMES utf8mb4;

-- 租户级门禁策略：每类评测一条，阈值均使用 EvalRun 的 0-1 归一化口径。
CREATE TABLE IF NOT EXISTS `ai_eval_release_gate_policy` (
    `id`                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`                VARCHAR(64) NOT NULL COMMENT '租户ID',
    `eval_type`                VARCHAR(16) NOT NULL COMMENT 'INTENT/QUALITY',
    `enabled`                  TINYINT NOT NULL DEFAULT 1 COMMENT '是否参与发布门禁',
    `min_primary_metric`       DOUBLE DEFAULT NULL COMMENT '主指标绝对下限',
    `min_secondary_metric`     DOUBLE DEFAULT NULL COMMENT '次指标绝对下限',
    `max_primary_regression`   DOUBLE DEFAULT NULL COMMENT '相对基线允许的主指标最大下降',
    `max_secondary_regression` DOUBLE DEFAULT NULL COMMENT '相对基线允许的次指标最大下降',
    `critical_case_ids_json`   TEXT COMMENT '零容忍关键用例ID数组',
    `judge_error_policy`       VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/ALLOW',
    `require_artifact_match`   TINYINT NOT NULL DEFAULT 1 COMMENT '评测版本是否必须匹配发布候选',
    `create_by`                BIGINT DEFAULT NULL,
    `create_time`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_by`                BIGINT DEFAULT NULL,
    `update_time`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                 ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY `uk_eval_gate_policy_tenant_type` (`tenant_id`, `eval_type`),
    KEY `idx_eval_gate_policy_enabled` (`tenant_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测发布门禁策略';

-- 紧急豁免只追加，绑定具体发布任务与已固化候选哈希，不能复用于下一次发布。
CREATE TABLE IF NOT EXISTS `ai_eval_release_gate_override` (
    `id`                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`              VARCHAR(64) NOT NULL COMMENT '租户ID',
    `task_id`                VARCHAR(64) NOT NULL COMMENT '可靠发布任务ID',
    `candidate_content_hash` VARCHAR(64) NOT NULL COMMENT '豁免对应的候选内容哈希',
    `operator_id`            BIGINT NOT NULL COMMENT '豁免操作人',
    `reason`                 VARCHAR(500) NOT NULL COMMENT '紧急豁免原因',
    `previous_decision_json` LONGTEXT COMMENT '豁免前的完整门禁判定',
    `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY `uk_eval_gate_override_task` (`tenant_id`, `task_id`),
    KEY `idx_eval_gate_override_operator` (`tenant_id`, `operator_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测发布门禁紧急豁免审计';

-- 门禁事实直接扩展既有可靠发布任务，不另建发布链。
ALTER TABLE `ai_runtime_publish_task`
    ADD COLUMN `candidate_versions_json` TEXT DEFAULT NULL COMMENT '待发布候选的可比版本绑定JSON' AFTER `last_error`,
    ADD COLUMN `gate_status` VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'NOT_REQUIRED/PENDING/PASSED/BLOCKED/OVERRIDDEN' AFTER `candidate_versions_json`,
    ADD COLUMN `gate_eval_run_ids_json` TEXT DEFAULT NULL COMMENT '本次判定使用的EvalRun ID数组' AFTER `gate_status`,
    ADD COLUMN `gate_decision_json` LONGTEXT DEFAULT NULL COMMENT '完整门禁判定JSON' AFTER `gate_eval_run_ids_json`,
    ADD COLUMN `gate_evaluated_at_ms` BIGINT DEFAULT NULL COMMENT '门禁判定时间戳' AFTER `gate_decision_json`,
    ADD COLUMN `gate_override_id` BIGINT DEFAULT NULL COMMENT '紧急豁免审计ID' AFTER `gate_evaluated_at_ms`,
    ADD KEY `idx_runtime_publish_gate` (`tenant_id`, `gate_status`, `seq`);

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 232, '编辑发布门禁策略', 'eval:gate-policy-edit', 2, 2
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'eval:gate-policy-edit');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 232, '紧急豁免发布门禁', 'eval:gate-override', 2, 3
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'eval:gate-override');
