-- 独立持久回执先受理再执行；试用不会写入正式智能体或工作区会话。
CREATE TABLE IF NOT EXISTS `ai_agent_draft_trial` (
    `id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `tenant_id` VARCHAR(64) NOT NULL,
    `owner_user_id` BIGINT NOT NULL,
    `draft_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `draft_version` BIGINT NOT NULL,
    `input` TEXT NOT NULL,
    `request_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `configuration_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `frozen_configuration` JSON NOT NULL COMMENT '不包含凭据的服务器冻结配置与资源版本',
    `phase` VARCHAR(24) NOT NULL,
    `result_json` JSON DEFAULT NULL,
    `error_code` VARCHAR(64) DEFAULT NULL,
    `accepted_at_ms` BIGINT NOT NULL,
    `deadline_at_ms` BIGINT NOT NULL,
    `finished_at_ms` BIGINT DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_draft_trial_owner` (`tenant_id`, `owner_user_id`, `draft_id`, `accepted_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='智能体个人草稿试用回执';
