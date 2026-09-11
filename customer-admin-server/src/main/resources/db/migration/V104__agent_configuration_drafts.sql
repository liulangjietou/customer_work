-- 个人配置草稿与可运行智能体分开存储；保存草稿不会发布配置或授予资源权限。
CREATE TABLE IF NOT EXISTS `ai_agent_draft` (
    `id` CHAR(36) NOT NULL,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default',
    `owner_user_id` BIGINT NOT NULL,
    `agent_id` BIGINT DEFAULT NULL COMMENT '为空表示新建智能体草稿',
    `base_revision` BIGINT DEFAULT NULL COMMENT '开始编辑时的运行配置修订号',
    `title` VARCHAR(200) NOT NULL,
    `configuration` JSON NOT NULL COMMENT '未完成的配置，不包含模型或工具凭据',
    `version` BIGINT NOT NULL DEFAULT 1 COMMENT '草稿并发编辑版本',
    `updated_at_ms` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_agent_draft_owner` (`tenant_id`, `owner_user_id`, `updated_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体个人配置草稿';
