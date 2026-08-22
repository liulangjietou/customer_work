-- P0 控制面实时撤权：用户/租户访问版本 + 租户访问快照可靠发布任务。
--
-- auth_epoch / access_epoch 都只允许通过领域命令原子递增。登录态保存当时的版本，
-- 后续请求若发现数据库版本已变化即强制重新登录，避免禁用、改密或冻结后旧会话继续使用。
ALTER TABLE `sys_user`
    ADD COLUMN `auth_epoch` BIGINT NOT NULL DEFAULT 0 COMMENT '认证版本；禁用、删号、改密或角色变化时递增';

ALTER TABLE `sys_tenant`
    ADD COLUMN `access_epoch` BIGINT NOT NULL DEFAULT 0 COMMENT '访问版本；冻结、恢复、退租或主动撤权时递增';

-- 每次租户访问状态变化都与业务修改同事务写入一条快照任务。
-- Worker 按 tenant_id 内的 seq 串行投递，避免同一租户的旧状态覆盖新状态。
CREATE TABLE IF NOT EXISTS `sys_tenant_access_publish_task` (
    `id` VARCHAR(64) NOT NULL,
    `seq` BIGINT NOT NULL AUTO_INCREMENT COMMENT '严格写入顺序',
    `tenant_id` VARCHAR(64) NOT NULL,
    `tenant_status` VARCHAR(16) NOT NULL,
    `access_epoch` BIGINT NOT NULL,
    `operation` VARCHAR(24) NOT NULL COMMENT 'PROVISION/EXPIRY_CHANGE/STATUS_CHANGE/SESSION_REVOKE/OFFBOARD',
    `session_revocation_status` VARCHAR(24) NOT NULL COMMENT 'NOT_REQUIRED/EPOCH_ENFORCED',
    `channel_disable_status` VARCHAR(24) NOT NULL COMMENT 'NOT_REQUIRED/COMPLETED',
    `channels_disabled_count` INT NOT NULL DEFAULT 0,
    `expire_time` DATETIME DEFAULT NULL,
    `data_id` VARCHAR(255) NOT NULL,
    `group_name` VARCHAR(128) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at_ms` BIGINT NOT NULL,
    `active_lease_key` VARCHAR(64) DEFAULT NULL COMMENT '处理中写租户ID；唯一键保证同租户仅一个发布者',
    `lease_owner` VARCHAR(128) DEFAULT NULL,
    `lease_until_ms` BIGINT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `published_at_ms` BIGINT DEFAULT NULL,
    `created_at_ms` BIGINT NOT NULL,
    `updated_at_ms` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_access_publish_seq` (`seq`),
    UNIQUE KEY `uk_tenant_access_publish_epoch` (`tenant_id`, `access_epoch`),
    UNIQUE KEY `uk_tenant_access_publish_active_lease` (`active_lease_key`),
    KEY `idx_tenant_access_publish_due` (`status`, `next_attempt_at_ms`, `lease_until_ms`),
    KEY `idx_tenant_access_publish_tenant` (`tenant_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户访问快照可靠发布任务';
