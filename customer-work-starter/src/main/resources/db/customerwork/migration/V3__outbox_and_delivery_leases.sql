-- 数据库 Outbox：与业务变更同库同事务提交，Handler 在提交后按至少一次语义投递。
CREATE TABLE IF NOT EXISTS `cw_outbox_message` (
    `tenant_id`          VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`                 VARCHAR(64) PRIMARY KEY COMMENT '消息ID，也是下游幂等键',
    `type`               VARCHAR(64) NOT NULL COMMENT 'Handler 类型',
    `aggregate_id`       VARCHAR(128) NOT NULL COMMENT '聚合根业务标识',
    `payload`            MEDIUMTEXT NOT NULL COMMENT '自包含 JSON 载荷',
    `status`             VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCEEDED/ABANDONED',
    `attempts`           INT NOT NULL DEFAULT 0 COMMENT '投递失败次数',
    `next_attempt_at_ms` BIGINT NOT NULL COMMENT '下次投递时间',
    `lease_owner`        VARCHAR(128) COMMENT '当前租约持有实例',
    `lease_until_ms`     BIGINT NOT NULL DEFAULT 0 COMMENT '租约到期时间',
    `last_error`         TEXT COMMENT '最近一次投递错误',
    `created_at_ms`      BIGINT NOT NULL COMMENT '创建时间',
    `finished_at_ms`     BIGINT NOT NULL DEFAULT 0 COMMENT '终态时间',
    INDEX `idx_outbox_due` (`tenant_id`, `status`, `next_attempt_at_ms`),
    INDEX `idx_outbox_lease` (`tenant_id`, `status`, `lease_until_ms`),
    INDEX `idx_outbox_aggregate` (`tenant_id`, `aggregate_id`, `created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同库事务 Outbox';

-- 死信重投增加租约；多实例先 CAS 抢租约，避免同一死信被并发重做。
ALTER TABLE `cw_dead_letter`
    ADD COLUMN `lease_owner` VARCHAR(128) NULL COMMENT '当前租约持有实例' AFTER `next_retry_at_ms`,
    ADD COLUMN `lease_until_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '租约到期时间' AFTER `lease_owner`,
    ADD INDEX `idx_dead_letter_lease` (`tenant_id`, `status`, `lease_until_ms`);
