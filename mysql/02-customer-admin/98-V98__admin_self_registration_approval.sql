-- 后台本地账号自助注册审核：账号启停与注册准入分离，并保留最近一次审核事实。
SET NAMES utf8mb4;

SET @v98_user_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'sys_user');
SET @v98_preflight_sql = IF(@v98_user_exists = 1,
    'SELECT 1', 'SELECT * FROM `__customer_admin_v98_sys_user_preflight_failed__`');
PREPARE v98_preflight_stmt FROM @v98_preflight_sql;
EXECUTE v98_preflight_stmt;
DEALLOCATE PREPARE v98_preflight_stmt;

SET @v98_user_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'sys_user' AND column_name = 'approval_status'), '',
       ', ADD COLUMN `approval_status` VARCHAR(16) NOT NULL DEFAULT ''APPROVED'' COMMENT ''注册审核状态：PENDING/APPROVED/REJECTED'' AFTER `status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'sys_user' AND column_name = 'approval_by'), '',
       ', ADD COLUMN `approval_by` BIGINT DEFAULT NULL COMMENT ''最近一次审核人 sys_user.id'' AFTER `approval_status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'sys_user' AND column_name = 'approval_time'), '',
       ', ADD COLUMN `approval_time` DATETIME DEFAULT NULL COMMENT ''最近一次审核时间'' AFTER `approval_by`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'sys_user' AND column_name = 'approval_remark'), '',
       ', ADD COLUMN `approval_remark` VARCHAR(255) DEFAULT NULL COMMENT ''最近一次审核说明或拒绝原因'' AFTER `approval_time`')
);
SET @v98_user_ddl = IF(@v98_user_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `sys_user` ', SUBSTRING(@v98_user_columns, 3)));
PREPARE v98_user_stmt FROM @v98_user_ddl;
EXECUTE v98_user_stmt;
DEALLOCATE PREPARE v98_user_stmt;

-- 兼容人工预建了可空列的环境；只补空值，不覆盖已经进入审核流程的状态。
UPDATE `sys_user`
SET `approval_status` = 'APPROVED'
WHERE `approval_status` IS NULL OR TRIM(`approval_status`) = '';

-- 人工预建列也要收敛到正式契约，不能只补值后仍留下 nullable / 无默认值的漂移结构。
ALTER TABLE `sys_user`
    MODIFY COLUMN `approval_status` VARCHAR(16) NOT NULL DEFAULT 'APPROVED'
        COMMENT '注册审核状态：PENDING/APPROVED/REJECTED' AFTER `status`;

SET @v98_approval_index_exists = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sys_user'
      AND index_name = 'idx_sys_user_approval');
SET @v98_approval_index_sql = IF(@v98_approval_index_exists > 0, 'SELECT 1',
    'ALTER TABLE `sys_user` ADD INDEX `idx_sys_user_approval` (`tenant_id`, `approval_status`, `deleted`, `create_time`)');
PREPARE v98_approval_index_stmt FROM @v98_approval_index_sql;
EXECUTE v98_approval_index_stmt;
DEALLOCATE PREPARE v98_approval_index_stmt;
