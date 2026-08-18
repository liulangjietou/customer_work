-- 工作区会话归属：框架状态表无 tenant/user 列，单独维护不可伪造的根资源所有权。
CREATE TABLE IF NOT EXISTS `ai_workspace_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` VARCHAR(64) NOT NULL,
    `agent_code` VARCHAR(64) NOT NULL,
    `session_id` VARCHAR(128) NOT NULL,
    `owner_user_id` BIGINT NOT NULL,
    `created_at_ms` BIGINT NOT NULL,
    `updated_at_ms` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_session_tenant_agent_session` (`tenant_id`, `agent_code`, `session_id`),
    KEY `idx_workspace_session_tenant_owner` (`tenant_id`, `owner_user_id`, `updated_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作区会话租户与用户归属';
