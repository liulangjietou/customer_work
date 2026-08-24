-- 运行时交付治理：冻结 ACK 目标、MCP SecretRef、maker-checker 与可靠审计留存。
-- MySQL DDL 不可事务回滚，所有 ALTER 均按实际缺列/缺索引生成，支持 repair 后安全重试。

SET NAMES utf8mb4;

SET @v89_table_count = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
      AND table_name IN ('ai_runtime_publish_task', 'ai_mcp', 'sys_operation_log',
                         'sys_permission', 'sys_role_permission'));
SET @v89_preflight_sql = IF(@v89_table_count = 5, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v89_required_tables_missing__`');
PREPARE v89_preflight_stmt FROM @v89_preflight_sql;
EXECUTE v89_preflight_stmt;
DEALLOCATE PREPARE v89_preflight_stmt;

-- 每个发布 revision 的 ACK 目标在任务入队时冻结；NULL 仅保留给历史任务兼容聚合。
SET @v89_publish_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_runtime_publish_task' AND column_name = 'ack_targets_json'), '',
       ', ADD COLUMN `ack_targets_json` TEXT DEFAULT NULL COMMENT ''入队时冻结的目标实例ID JSON'' AFTER `content_hash`')
);
SET @v89_publish_ddl = IF(@v89_publish_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_runtime_publish_task` ', SUBSTRING(@v89_publish_columns, 3)));
PREPARE v89_publish_stmt FROM @v89_publish_ddl;
EXECUTE v89_publish_stmt;
DEALLOCATE PREPARE v89_publish_stmt;

-- MCP config 只保留不可执行占位符，凭据材料进入 SecretRef。
SET @v89_mcp_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_mcp' AND column_name = 'secret_ref_id'), '',
       ', ADD COLUMN `secret_ref_id` BIGINT DEFAULT NULL COMMENT ''MCP 敏感配置 SecretRef'' AFTER `config`')
);
SET @v89_mcp_column_ddl = IF(@v89_mcp_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_mcp` ', SUBSTRING(@v89_mcp_columns, 3)));
PREPARE v89_mcp_column_stmt FROM @v89_mcp_column_ddl;
EXECUTE v89_mcp_column_stmt;
DEALLOCATE PREPARE v89_mcp_column_stmt;

SET @v89_mcp_indexes = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'ai_mcp' AND index_name = 'idx_ai_mcp_secret_ref'), '',
       ', ADD INDEX `idx_ai_mcp_secret_ref` (`secret_ref_id`)')
);
SET @v89_mcp_index_ddl = IF(@v89_mcp_indexes = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_mcp` ', SUBSTRING(@v89_mcp_indexes, 3)));
PREPARE v89_mcp_index_stmt FROM @v89_mcp_index_ddl;
EXECUTE v89_mcp_index_stmt;
DEALLOCATE PREPARE v89_mcp_index_stmt;

-- 通用操作审计先同步落 STARTED，再补写 COMPLETED；终态写失败时仍保留不确定事实。
SET @v89_log_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'sys_operation_log' AND column_name = 'event_id'), '',
       ', ADD COLUMN `event_id` VARCHAR(36) DEFAULT NULL COMMENT ''一次操作的稳定审计事件ID'' AFTER `ip`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'sys_operation_log' AND column_name = 'audit_status'), '',
       ', ADD COLUMN `audit_status` VARCHAR(16) NOT NULL DEFAULT ''COMPLETED'' COMMENT ''STARTED/COMPLETED'' AFTER `event_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'sys_operation_log' AND column_name = 'retention_until'), '',
       ', ADD COLUMN `retention_until` DATETIME DEFAULT NULL COMMENT ''最短留存截止时间'' AFTER `audit_status`')
);
SET @v89_log_column_ddl = IF(@v89_log_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `sys_operation_log` ', SUBSTRING(@v89_log_columns, 3)));
PREPARE v89_log_column_stmt FROM @v89_log_column_ddl;
EXECUTE v89_log_column_stmt;
DEALLOCATE PREPARE v89_log_column_stmt;

UPDATE `sys_operation_log`
SET `retention_until` = DATE_ADD(`create_time`, INTERVAL 3650 DAY)
WHERE `retention_until` IS NULL;

SET @v89_log_indexes = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'sys_operation_log' AND index_name = 'uk_sys_operation_log_event'), '',
       ', ADD UNIQUE INDEX `uk_sys_operation_log_event` (`event_id`)'),
    IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'sys_operation_log' AND index_name = 'idx_sys_operation_log_retention'), '',
       ', ADD INDEX `idx_sys_operation_log_retention` (`audit_status`, `retention_until`)')
);
SET @v89_log_index_ddl = IF(@v89_log_indexes = '', 'SELECT 1',
    CONCAT('ALTER TABLE `sys_operation_log` ', SUBSTRING(@v89_log_indexes, 3)));
PREPARE v89_log_index_stmt FROM @v89_log_index_ddl;
EXECUTE v89_log_index_stmt;
DEALLOCATE PREPARE v89_log_index_stmt;

CREATE TABLE IF NOT EXISTS `ai_governed_change_request` (
    `id`                VARCHAR(36) PRIMARY KEY COMMENT '审批请求ID',
    `tenant_id`         VARCHAR(64) NOT NULL COMMENT '租户ID',
    `change_type`       VARCHAR(64) NOT NULL COMMENT '类型化高风险变更',
    `target_key`        VARCHAR(160) NOT NULL COMMENT '稳定目标键',
    `payload_json`      MEDIUMTEXT NOT NULL COMMENT '服务端类型化执行载荷，不含凭据',
    `payload_hash`      CHAR(64) NOT NULL COMMENT '执行载荷 SHA-256',
    `maker_id`          BIGINT NOT NULL COMMENT '发起人',
    `maker_name`        VARCHAR(64) DEFAULT NULL COMMENT '发起人账号快照',
    `checker_id`        BIGINT DEFAULT NULL COMMENT '复核人',
    `checker_name`      VARCHAR(64) DEFAULT NULL COMMENT '复核人账号快照',
    `status`            VARCHAR(16) NOT NULL COMMENT 'PENDING/EXECUTING/EXECUTED/REJECTED/FAILED/EXPIRED',
    `decision_reason`   VARCHAR(500) DEFAULT NULL COMMENT '复核理由',
    `result_json`       MEDIUMTEXT DEFAULT NULL COMMENT '脱敏执行结果',
    `failure_code`      VARCHAR(64) DEFAULT NULL COMMENT '稳定失败码，不保存异常明文',
    `expires_at`        DATETIME(3) NOT NULL COMMENT '审批到期时间',
    `decided_at`        DATETIME(3) DEFAULT NULL COMMENT '复核时间',
    `executed_at`       DATETIME(3) DEFAULT NULL COMMENT '执行终止时间',
    `create_time`       DATETIME(3) NOT NULL COMMENT '创建时间',
    `update_time`       DATETIME(3) NOT NULL COMMENT '更新时间',
    KEY `idx_governed_change_tenant_status` (`tenant_id`, `status`, `create_time` DESC),
    KEY `idx_governed_change_expiry` (`status`, `expires_at`),
    KEY `idx_governed_change_execution_recovery` (`status`, `update_time`),
    KEY `idx_governed_change_target` (`tenant_id`, `change_type`, `target_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='maker-checker 高风险变更请求';

CREATE TABLE IF NOT EXISTS `ai_governance_audit_event` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64) NOT NULL COMMENT '租户ID',
    `request_id`        VARCHAR(36) NOT NULL COMMENT '审批请求ID',
    `sequence_no`       INT NOT NULL COMMENT '请求内单调事件序号',
    `event_type`        VARCHAR(32) NOT NULL COMMENT 'SUBMITTED/APPROVED/EXECUTED/REJECTED/FAILED/EXPIRED',
    `actor_id`          BIGINT DEFAULT NULL COMMENT '操作人；系统事件为空',
    `actor_name`        VARCHAR(64) DEFAULT NULL COMMENT '操作人账号快照',
    `payload_hash`      CHAR(64) NOT NULL COMMENT '审批载荷 SHA-256',
    `detail`            VARCHAR(500) DEFAULT NULL COMMENT '不含敏感数据的审计摘要',
    `previous_hash`     CHAR(64) NOT NULL COMMENT '前一事件哈希',
    `event_hash`        CHAR(64) NOT NULL COMMENT '当前事件哈希',
    `retention_until`   DATETIME NOT NULL COMMENT '最短留存截止时间',
    `create_time`       DATETIME(3) NOT NULL COMMENT '事件时间',
    UNIQUE KEY `uk_governance_audit_request_seq` (`tenant_id`, `request_id`, `sequence_no`),
    UNIQUE KEY `uk_governance_audit_event_hash` (`event_hash`),
    KEY `idx_governance_audit_retention` (`retention_until`),
    KEY `idx_governance_audit_request` (`tenant_id`, `request_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='追加写治理审计哈希链';

-- 审批入口复用配置版本页面；具备回滚权限的控制面角色获得查看/复核按钮，服务端仍强制 maker != checker。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT `id`, '查看高风险审批', 'governance:view', 2, 3
FROM `sys_permission` WHERE `perm_code` = 'config-version:view'
  AND NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'governance:view');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT `id`, '复核高风险变更', 'governance:approve', 2, 4
FROM `sys_permission` WHERE `perm_code` = 'config-version:view'
  AND NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'governance:approve');

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `tenant_id`)
SELECT DISTINCT `rp`.`role_id`, `new_permission`.`id`, `rp`.`tenant_id`
FROM `sys_role_permission` `rp`
JOIN `sys_permission` `existing_permission`
  ON `existing_permission`.`id` = `rp`.`permission_id`
 AND `existing_permission`.`perm_code` = 'config-version:rollback'
JOIN `sys_permission` `new_permission`
  ON `new_permission`.`perm_code` IN ('governance:view', 'governance:approve')
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_role_permission` `existing_grant`
    WHERE `existing_grant`.`role_id` = `rp`.`role_id`
      AND `existing_grant`.`permission_id` = `new_permission`.`id`
);
