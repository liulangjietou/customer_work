SET NAMES utf8mb4;

-- 历史配置不再作为完整运行时载荷直发。可靠任务只保存提示词与 maxIters 白名单补丁，
-- Worker 在目标租户下用当前模型、SecretRef、MCP、路由与在线实验重组候选，再走既有 Eval/ACK 链路。
ALTER TABLE `ai_runtime_publish_task`
    ADD COLUMN `operation_id` VARCHAR(64) DEFAULT NULL COMMENT '一次回滚/灰度操作ID；灰度多任务共用' AFTER `experiment_publish_action`,
    ADD COLUMN `publish_intent` VARCHAR(24) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/SAFE_ROLLBACK/SAFE_GRAY' AFTER `operation_id`,
    ADD COLUMN `source_config_version_id` BIGINT DEFAULT NULL COMMENT '白名单补丁来源配置版本主键' AFTER `publish_intent`,
    ADD COLUMN `source_content_hash` CHAR(64) DEFAULT NULL COMMENT '来源完整快照SHA-256，仅作完整性审计' AFTER `source_config_version_id`,
    ADD COLUMN `rollback_patch_json` JSON DEFAULT NULL COMMENT '仅允许systemPrompt/maxIters，不含模型、凭据、MCP、路由与实验' AFTER `source_content_hash`;

-- 存量常规任务以自身任务 ID 作为单任务 operation，确保后续所有任务都有可追踪操作号。
-- operation_id 暂留可空以兼容滚动发布期间尚未升级的旧 Pod；安全意图由 CHECK 强制非空。
UPDATE `ai_runtime_publish_task`
SET `operation_id` = `id`
WHERE `operation_id` IS NULL;

ALTER TABLE `ai_runtime_publish_task`
    ADD KEY `idx_runtime_publish_operation` (`tenant_id`, `operation_id`, `seq`),
    ADD KEY `idx_runtime_publish_source_version` (`tenant_id`, `source_config_version_id`, `seq`),
    ADD CONSTRAINT `chk_runtime_publish_safe_intent`
        CHECK (
            (`publish_intent` = 'NORMAL'
                AND `source_config_version_id` IS NULL
                AND `source_content_hash` IS NULL
                AND `rollback_patch_json` IS NULL)
            OR
            (`publish_intent` IN ('SAFE_ROLLBACK', 'SAFE_GRAY')
                AND `operation_id` IS NOT NULL
                AND `source_config_version_id` IS NOT NULL
                AND `source_content_hash` IS NOT NULL
                AND `rollback_patch_json` IS NOT NULL)
        );
