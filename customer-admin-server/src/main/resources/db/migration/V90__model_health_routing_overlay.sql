-- 模型健康动态路由 overlay：连续恢复、冷却、人工覆盖和可审计状态迁移。
-- MySQL DDL 不可事务回滚，所有 ALTER 均按实际列/索引状态生成，支持 repair 后重试。

SET NAMES utf8mb4;

SET @v90_table_count = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
      AND table_name IN ('ai_model_health_snapshot', 'ai_model_health_event',
                         'sys_permission', 'sys_role_permission'));
SET @v90_preflight_sql = IF(@v90_table_count = 4, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v90_required_tables_missing__`');
PREPARE v90_preflight_stmt FROM @v90_preflight_sql;
EXECUTE v90_preflight_stmt;
DEALLOCATE PREPARE v90_preflight_stmt;

SET @v90_snapshot_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND column_name = 'consecutive_successes'), '',
       ', ADD COLUMN `consecutive_successes` INT NOT NULL DEFAULT 0 COMMENT ''连续成功次数'' AFTER `consecutive_failures`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND column_name = 'cooldown_until'), '',
       ', ADD COLUMN `cooldown_until` DATETIME(6) DEFAULT NULL COMMENT ''UNHEALTHY 冷却截止时间'' AFTER `next_probe_at`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND column_name = 'override_mode'), '',
       ', ADD COLUMN `override_mode` VARCHAR(24) NOT NULL DEFAULT ''AUTO'' COMMENT ''AUTO/FORCE_HEALTHY/FORCE_UNHEALTHY'' AFTER `cooldown_until`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND column_name = 'override_reason'), '',
       ', ADD COLUMN `override_reason` VARCHAR(500) DEFAULT NULL COMMENT ''人工覆盖理由'' AFTER `override_mode`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND column_name = 'override_operator_id'), '',
       ', ADD COLUMN `override_operator_id` BIGINT DEFAULT NULL COMMENT ''覆盖操作人'' AFTER `override_reason`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND column_name = 'override_operator_name'), '',
       ', ADD COLUMN `override_operator_name` VARCHAR(64) DEFAULT NULL COMMENT ''覆盖操作人账号快照'' AFTER `override_operator_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND column_name = 'override_until'), '',
       ', ADD COLUMN `override_until` DATETIME(6) DEFAULT NULL COMMENT ''人工覆盖到期时间'' AFTER `override_operator_name`')
);
SET @v90_snapshot_column_ddl = IF(@v90_snapshot_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_model_health_snapshot` ', SUBSTRING(@v90_snapshot_columns, 3)));
PREPARE v90_snapshot_column_stmt FROM @v90_snapshot_column_ddl;
EXECUTE v90_snapshot_column_stmt;
DEALLOCATE PREPARE v90_snapshot_column_stmt;

UPDATE `ai_model_health_snapshot`
SET `consecutive_successes` = CASE
        WHEN `health_status` = 'RECOVERING' THEN 1
        WHEN `health_status` = 'HEALTHY' THEN 2
        ELSE 0
    END,
    `override_mode` = 'AUTO'
WHERE `consecutive_successes` = 0
  AND `health_status` IN ('HEALTHY', 'RECOVERING');

SET @v90_snapshot_indexes = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_snapshot' AND index_name = 'idx_model_health_override_expiry'), '',
       ', ADD INDEX `idx_model_health_override_expiry` (`override_mode`, `override_until`)')
);
SET @v90_snapshot_index_ddl = IF(@v90_snapshot_indexes = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_model_health_snapshot` ', SUBSTRING(@v90_snapshot_indexes, 3)));
PREPARE v90_snapshot_index_stmt FROM @v90_snapshot_index_ddl;
EXECUTE v90_snapshot_index_stmt;
DEALLOCATE PREPARE v90_snapshot_index_stmt;

SET @v90_event_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_event' AND column_name = 'event_type'), '',
       ', ADD COLUMN `event_type` VARCHAR(32) NOT NULL DEFAULT ''PROBE'' COMMENT ''PROBE/STATE_TRANSITION/STALE_PROBE/OVERRIDE_*'' AFTER `model_config_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_event' AND column_name = 'previous_health_status'), '',
       ', ADD COLUMN `previous_health_status` VARCHAR(16) DEFAULT NULL COMMENT ''事件前原始健康状态'' AFTER `probe_kind`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_event' AND column_name = 'effective_health_status'), '',
       ', ADD COLUMN `effective_health_status` VARCHAR(16) DEFAULT NULL COMMENT ''覆盖后的有效健康状态'' AFTER `health_status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_event' AND column_name = 'override_mode'), '',
       ', ADD COLUMN `override_mode` VARCHAR(24) NOT NULL DEFAULT ''AUTO'' COMMENT ''事件对应覆盖模式'' AFTER `effective_health_status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_event' AND column_name = 'operator_id'), '',
       ', ADD COLUMN `operator_id` BIGINT DEFAULT NULL COMMENT ''人工覆盖操作人'' AFTER `override_mode`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_health_event' AND column_name = 'operator_name'), '',
       ', ADD COLUMN `operator_name` VARCHAR(64) DEFAULT NULL COMMENT ''人工覆盖账号快照'' AFTER `operator_id`')
);
SET @v90_event_column_ddl = IF(@v90_event_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_model_health_event` ', SUBSTRING(@v90_event_columns, 3)));
PREPARE v90_event_column_stmt FROM @v90_event_column_ddl;
EXECUTE v90_event_column_stmt;
DEALLOCATE PREPARE v90_event_column_stmt;

UPDATE `ai_model_health_event`
SET `event_type` = 'PROBE',
    `effective_health_status` = `health_status`,
    `override_mode` = 'AUTO'
WHERE `effective_health_status` IS NULL;

SET @v90_event_test_status_nullable = (
    SELECT CASE WHEN `IS_NULLABLE` = 'YES' THEN 1 ELSE 0 END
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_model_health_event'
      AND column_name = 'test_status'
);
SET @v90_event_test_status_sql = IF(@v90_event_test_status_nullable = 1, 'SELECT 1',
    'ALTER TABLE `ai_model_health_event` MODIFY COLUMN `test_status` TINYINT DEFAULT NULL COMMENT ''探测事件为0/1/2，覆盖事件为空''');
PREPARE v90_event_test_status_stmt FROM @v90_event_test_status_sql;
EXECUTE v90_event_test_status_stmt;
DEALLOCATE PREPARE v90_event_test_status_stmt;

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 20, '模型健康路由覆盖', 'model:health-override', 2, 7
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'model:health-override');

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `tenant_id`)
SELECT DISTINCT `rp`.`role_id`, `override_permission`.`id`, `rp`.`tenant_id`
FROM `sys_role_permission` `rp`
JOIN `sys_permission` `health_permission`
  ON `health_permission`.`id` = `rp`.`permission_id`
 AND `health_permission`.`perm_code` = 'model:health-test'
JOIN `sys_permission` `override_permission`
  ON `override_permission`.`perm_code` = 'model:health-override'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_role_permission` `existing_grant`
    WHERE `existing_grant`.`role_id` = `rp`.`role_id`
      AND `existing_grant`.`permission_id` = `override_permission`.`id`
);
