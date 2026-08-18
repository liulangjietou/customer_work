-- 运行时配置发布任务：业务变更与任务同库事务落盘，worker 租约投递 Nacos。
CREATE TABLE IF NOT EXISTS `ai_runtime_publish_task` (
    `id` VARCHAR(64) NOT NULL,
    `seq` BIGINT NOT NULL AUTO_INCREMENT COMMENT '严格写入顺序，避免同毫秒任务排序不确定',
    `tenant_id` VARCHAR(64) NOT NULL,
    `target_code` VARCHAR(64) DEFAULT NULL,
    `target_id` BIGINT NOT NULL,
    `channel_code` VARCHAR(64) DEFAULT NULL,
    `data_id` VARCHAR(255) DEFAULT NULL,
    `group_name` VARCHAR(128) DEFAULT NULL,
    `revision` VARCHAR(64) DEFAULT NULL,
    `content_hash` VARCHAR(64) DEFAULT NULL,
    `publish_scope` VARCHAR(16) NOT NULL DEFAULT 'FULL',
    `gray_tenants` TEXT,
    `source_version` INT DEFAULT NULL,
    `remark` VARCHAR(500) DEFAULT NULL,
    `status` VARCHAR(16) NOT NULL,
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at_ms` BIGINT NOT NULL,
    `lease_owner` VARCHAR(128) DEFAULT NULL,
    `lease_until_ms` BIGINT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `created_at_ms` BIGINT NOT NULL,
    `updated_at_ms` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_publish_seq` (`seq`),
    UNIQUE KEY `uk_runtime_publish_tenant_revision` (`tenant_id`, `revision`),
    KEY `idx_runtime_publish_due` (`status`, `next_attempt_at_ms`, `lease_until_ms`),
    KEY `idx_runtime_publish_target` (`tenant_id`, `target_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时配置可靠发布任务';

-- 实例回执：以 revision + instance 幂等覆盖，记录真实 APPLIED / REJECTED。
CREATE TABLE IF NOT EXISTS `ai_runtime_config_ack` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` VARCHAR(64) NOT NULL,
    `revision` VARCHAR(64) NOT NULL,
    `content_hash` VARCHAR(64) DEFAULT NULL,
    `instance_id` VARCHAR(128) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `reason` VARCHAR(1000) DEFAULT NULL,
    `applied_at_ms` BIGINT NOT NULL,
    `created_at_ms` BIGINT NOT NULL,
    `updated_at_ms` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_ack_tenant_revision_instance` (`tenant_id`, `revision`, `instance_id`),
    KEY `idx_runtime_ack_revision_status` (`tenant_id`, `revision`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时配置实例应用回执';
