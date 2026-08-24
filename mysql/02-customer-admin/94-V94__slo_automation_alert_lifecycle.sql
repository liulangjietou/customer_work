-- SLO 周期评估租约、告警状态机、恢复事件与可靠通知任务。
SET NAMES utf8mb4;

SET @v94_slo_table_count = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name IN ('ai_slo_policy', 'ai_slo_alert', 'sys_permission'));
SET @v94_slo_preflight_sql = IF(@v94_slo_table_count = 3, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v94_required_tables_missing__`');
PREPARE v94_slo_preflight_stmt FROM @v94_slo_preflight_sql;
EXECUTE v94_slo_preflight_stmt;
DEALLOCATE PREPARE v94_slo_preflight_stmt;

SET @v94_policy_next_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND column_name = 'next_evaluation_at_ms'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD COLUMN `next_evaluation_at_ms` BIGINT NOT NULL DEFAULT 0 COMMENT ''下次周期评估时间'' AFTER `enabled`');
PREPARE v94_policy_next_stmt FROM @v94_policy_next_sql;
EXECUTE v94_policy_next_stmt;
DEALLOCATE PREPARE v94_policy_next_stmt;

SET @v94_policy_owner_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND column_name = 'evaluation_lease_owner'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD COLUMN `evaluation_lease_owner` VARCHAR(160) DEFAULT NULL COMMENT ''周期评估租约持有者'' AFTER `next_evaluation_at_ms`');
PREPARE v94_policy_owner_stmt FROM @v94_policy_owner_sql;
EXECUTE v94_policy_owner_stmt;
DEALLOCATE PREPARE v94_policy_owner_stmt;

SET @v94_policy_lease_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND column_name = 'evaluation_lease_until_ms'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD COLUMN `evaluation_lease_until_ms` BIGINT NOT NULL DEFAULT 0 COMMENT ''周期评估租约截止时间'' AFTER `evaluation_lease_owner`');
PREPARE v94_policy_lease_stmt FROM @v94_policy_lease_sql;
EXECUTE v94_policy_lease_stmt;
DEALLOCATE PREPARE v94_policy_lease_stmt;

SET @v94_policy_failures_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND column_name = 'evaluation_failures'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD COLUMN `evaluation_failures` INT NOT NULL DEFAULT 0 COMMENT ''连续评估失败次数'' AFTER `evaluation_lease_until_ms`');
PREPARE v94_policy_failures_stmt FROM @v94_policy_failures_sql;
EXECUTE v94_policy_failures_stmt;
DEALLOCATE PREPARE v94_policy_failures_stmt;

SET @v94_policy_last_at_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND column_name = 'last_evaluated_at'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD COLUMN `last_evaluated_at` DATETIME DEFAULT NULL COMMENT ''最近评估时间'' AFTER `evaluation_failures`');
PREPARE v94_policy_last_at_stmt FROM @v94_policy_last_at_sql;
EXECUTE v94_policy_last_at_stmt;
DEALLOCATE PREPARE v94_policy_last_at_stmt;

SET @v94_policy_last_status_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND column_name = 'last_evaluation_status'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD COLUMN `last_evaluation_status` VARCHAR(32) DEFAULT NULL COMMENT ''最近评估状态'' AFTER `last_evaluated_at`');
PREPARE v94_policy_last_status_stmt FROM @v94_policy_last_status_sql;
EXECUTE v94_policy_last_status_stmt;
DEALLOCATE PREPARE v94_policy_last_status_stmt;

SET @v94_policy_last_error_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND column_name = 'last_evaluation_error'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD COLUMN `last_evaluation_error` VARCHAR(1000) DEFAULT NULL COMMENT ''最近评估错误'' AFTER `last_evaluation_status`');
PREPARE v94_policy_last_error_stmt FROM @v94_policy_last_error_sql;
EXECUTE v94_policy_last_error_stmt;
DEALLOCATE PREPARE v94_policy_last_error_stmt;

SET @v94_policy_due_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_policy' AND index_name = 'idx_slo_policy_evaluation_due'),
    'SELECT 1', 'ALTER TABLE `ai_slo_policy` ADD INDEX `idx_slo_policy_evaluation_due` (`enabled`, `next_evaluation_at_ms`, `evaluation_lease_until_ms`)');
PREPARE v94_policy_due_index_stmt FROM @v94_policy_due_index_sql;
EXECUTE v94_policy_due_index_stmt;
DEALLOCATE PREPARE v94_policy_due_index_stmt;

SET @v94_alert_active_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND column_name = 'active_policy_id'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD COLUMN `active_policy_id` BIGINT DEFAULT NULL COMMENT ''活跃告警策略ID，恢复后置空'' AFTER `alert_type`');
PREPARE v94_alert_active_stmt FROM @v94_alert_active_sql;
EXECUTE v94_alert_active_stmt;
DEALLOCATE PREPARE v94_alert_active_stmt;

SET @v94_alert_status_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND column_name = 'status'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT ''OPEN'' COMMENT ''OPEN/ACKED/RESOLVED'' AFTER `active_policy_id`');
PREPARE v94_alert_status_stmt FROM @v94_alert_status_sql;
EXECUTE v94_alert_status_stmt;
DEALLOCATE PREPARE v94_alert_status_stmt;

SET @v94_alert_last_seen_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND column_name = 'last_seen_at'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD COLUMN `last_seen_at` DATETIME DEFAULT NULL COMMENT ''最近燃烧或恢复观测时间'' AFTER `first_seen_at`');
PREPARE v94_alert_last_seen_stmt FROM @v94_alert_last_seen_sql;
EXECUTE v94_alert_last_seen_stmt;
DEALLOCATE PREPARE v94_alert_last_seen_stmt;

SET @v94_alert_ack_by_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND column_name = 'ack_by'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD COLUMN `ack_by` BIGINT DEFAULT NULL COMMENT ''确认人'' AFTER `last_seen_at`');
PREPARE v94_alert_ack_by_stmt FROM @v94_alert_ack_by_sql;
EXECUTE v94_alert_ack_by_stmt;
DEALLOCATE PREPARE v94_alert_ack_by_stmt;

SET @v94_alert_ack_at_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND column_name = 'ack_at'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD COLUMN `ack_at` DATETIME DEFAULT NULL COMMENT ''确认时间'' AFTER `ack_by`');
PREPARE v94_alert_ack_at_stmt FROM @v94_alert_ack_at_sql;
EXECUTE v94_alert_ack_at_stmt;
DEALLOCATE PREPARE v94_alert_ack_at_stmt;

SET @v94_alert_resolved_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND column_name = 'resolved_at'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD COLUMN `resolved_at` DATETIME DEFAULT NULL COMMENT ''恢复时间'' AFTER `ack_at`');
PREPARE v94_alert_resolved_stmt FROM @v94_alert_resolved_sql;
EXECUTE v94_alert_resolved_stmt;
DEALLOCATE PREPARE v94_alert_resolved_stmt;

SET @v94_alert_updated_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND column_name = 'update_time'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD COLUMN `update_time` DATETIME DEFAULT NULL COMMENT ''状态更新时间'' AFTER `resolved_at`');
PREPARE v94_alert_updated_stmt FROM @v94_alert_updated_sql;
EXECUTE v94_alert_updated_stmt;
DEALLOCATE PREPARE v94_alert_updated_stmt;

-- 旧表按每个策略最新一条保留为 OPEN，其余历史分钟事实转成 RESOLVED；只处理尚未回填的旧行。
UPDATE `ai_slo_alert` alert_row
JOIN (
    SELECT legacy.tenant_id, legacy.policy_id, MAX(legacy.id) AS latest_id
    FROM (
        SELECT id, tenant_id, policy_id
        FROM `ai_slo_alert`
        WHERE last_seen_at IS NULL
    ) legacy
    GROUP BY legacy.tenant_id, legacy.policy_id
) latest ON latest.tenant_id = alert_row.tenant_id AND latest.policy_id = alert_row.policy_id
SET alert_row.status = IF(alert_row.id = latest.latest_id, 'OPEN', 'RESOLVED'),
    alert_row.active_policy_id = IF(alert_row.id = latest.latest_id, alert_row.policy_id, NULL),
    alert_row.last_seen_at = alert_row.first_seen_at,
    alert_row.resolved_at = IF(alert_row.id = latest.latest_id, NULL, alert_row.first_seen_at),
    alert_row.update_time = alert_row.first_seen_at
WHERE alert_row.last_seen_at IS NULL;

-- 若上次手工执行中途停止后曾短暂产生多个活跃行，重跑时确定性保留最新一条再创建唯一键。
UPDATE `ai_slo_alert` active_row
JOIN (
    SELECT candidate.tenant_id, candidate.policy_id, MAX(candidate.id) AS latest_id
    FROM (
        SELECT id, tenant_id, policy_id
        FROM `ai_slo_alert`
        WHERE status IN ('OPEN', 'ACKED')
    ) candidate
    GROUP BY candidate.tenant_id, candidate.policy_id
) latest ON latest.tenant_id = active_row.tenant_id AND latest.policy_id = active_row.policy_id
SET active_row.status = 'RESOLVED', active_row.active_policy_id = NULL,
    active_row.resolved_at = COALESCE(active_row.resolved_at, active_row.last_seen_at, active_row.first_seen_at),
    active_row.update_time = COALESCE(active_row.update_time, active_row.last_seen_at, active_row.first_seen_at)
WHERE active_row.status IN ('OPEN', 'ACKED') AND active_row.id <> latest.latest_id;

UPDATE `ai_slo_alert`
SET active_policy_id = policy_id
WHERE status IN ('OPEN', 'ACKED');

SET @v94_drop_minute_unique_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert'
      AND index_name = 'uk_slo_alert_fact' AND non_unique = 0),
    'ALTER TABLE `ai_slo_alert` DROP INDEX `uk_slo_alert_fact`', 'SELECT 1');
PREPARE v94_drop_minute_unique_stmt FROM @v94_drop_minute_unique_sql;
EXECUTE v94_drop_minute_unique_stmt;
DEALLOCATE PREPARE v94_drop_minute_unique_stmt;

SET @v94_alert_active_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND index_name = 'uk_slo_alert_active'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD UNIQUE INDEX `uk_slo_alert_active` (`tenant_id`, `active_policy_id`)');
PREPARE v94_alert_active_index_stmt FROM @v94_alert_active_index_sql;
EXECUTE v94_alert_active_index_stmt;
DEALLOCATE PREPARE v94_alert_active_index_stmt;

SET @v94_alert_status_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_slo_alert' AND index_name = 'idx_slo_alert_tenant_status'),
    'SELECT 1', 'ALTER TABLE `ai_slo_alert` ADD INDEX `idx_slo_alert_tenant_status` (`tenant_id`, `status`, `last_seen_at`)');
PREPARE v94_alert_status_index_stmt FROM @v94_alert_status_index_sql;
EXECUTE v94_alert_status_index_stmt;
DEALLOCATE PREPARE v94_alert_status_index_stmt;

CREATE TABLE IF NOT EXISTS `ai_slo_alert_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` VARCHAR(64) NOT NULL,
    `alert_id` BIGINT NOT NULL,
    `policy_id` BIGINT NOT NULL,
    `event_type` VARCHAR(16) NOT NULL COMMENT 'OPENED/ACKED/RESOLVED',
    `actor_user_id` BIGINT DEFAULT NULL,
    `short_burn_rate` DECIMAL(12,6) NOT NULL,
    `long_burn_rate` DECIMAL(12,6) NOT NULL,
    `occurred_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slo_alert_event_once` (`alert_id`, `event_type`),
    KEY `idx_slo_alert_event_tenant_time` (`tenant_id`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO告警状态迁移事件';

CREATE TABLE IF NOT EXISTS `ai_slo_notification_task` (
    `id` VARCHAR(64) NOT NULL,
    `tenant_id` VARCHAR(64) NOT NULL,
    `event_id` BIGINT NOT NULL,
    `alert_id` BIGINT NOT NULL,
    `policy_id` BIGINT NOT NULL,
    `event_type` VARCHAR(16) NOT NULL,
    `title` VARCHAR(200) NOT NULL,
    `content` VARCHAR(1000) NOT NULL,
    `status` VARCHAR(16) NOT NULL COMMENT 'PENDING/PROCESSING/DELIVERED',
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at_ms` BIGINT NOT NULL,
    `lease_owner` VARCHAR(160) DEFAULT NULL,
    `lease_until_ms` BIGINT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `recipient_count` INT NOT NULL DEFAULT 0,
    `created_at_ms` BIGINT NOT NULL,
    `updated_at_ms` BIGINT NOT NULL,
    `delivered_at_ms` BIGINT DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slo_notification_event` (`event_id`),
    KEY `idx_slo_notification_due` (`status`, `next_attempt_at_ms`, `lease_until_ms`),
    KEY `idx_slo_notification_tenant` (`tenant_id`, `created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO告警可靠通知任务';

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT p.id, '确认 SLO 告警', 'slo:ack', 2, 3
FROM `sys_permission` p
WHERE p.perm_code = 'slo:view'
  AND NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'slo:ack');
