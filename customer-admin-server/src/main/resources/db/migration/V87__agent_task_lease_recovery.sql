-- 后台 Agent 任务从“单 Pod 内存 future + 启动全局置失败”升级为数据库租约所有权。
-- fire-and-forget 的 agent_spawn 原始输入由 Acting 中间件捕获，持久化后可由其它 Pod 重建子智能体执行。

SET @v87_table_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'ai_agent_task');
SET @v87_preflight_sql = IF(@v87_table_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v87_ai_agent_task_required__`');
PREPARE v87_preflight_stmt FROM @v87_preflight_sql;
EXECUTE v87_preflight_stmt;
DEALLOCATE PREPARE v87_preflight_stmt;

-- 按列补齐，允许 DDL 中断后 repair + 重试，不修改已部署过的历史迁移。
SET @v87_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'owner_id'), '', ', ADD COLUMN `owner_id` VARCHAR(128) DEFAULT NULL COMMENT ''当前执行所有者（Pod/进程唯一ID）'' AFTER `cancel_requested`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'lease_until'), '', ', ADD COLUMN `lease_until` DATETIME(3) DEFAULT NULL COMMENT ''所有权租约到期时间'' AFTER `owner_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'heartbeat_at'), '', ', ADD COLUMN `heartbeat_at` DATETIME(3) DEFAULT NULL COMMENT ''当前所有者最近心跳'' AFTER `lease_until`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'attempt_count'), '', ', ADD COLUMN `attempt_count` INT NOT NULL DEFAULT 0 COMMENT ''领取执行次数'' AFTER `heartbeat_at`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'replayable'), '', ', ADD COLUMN `replayable` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否具备可重放执行输入'' AFTER `attempt_count`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'task_input'), '', ', ADD COLUMN `task_input` MEDIUMTEXT DEFAULT NULL COMMENT ''子智能体原始任务提示词'' AFTER `replayable`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'child_session_id'), '', ', ADD COLUMN `child_session_id` VARCHAR(128) DEFAULT NULL COMMENT ''恢复执行的稳定子会话ID'' AFTER `task_input`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'runtime_user_id'), '', ', ADD COLUMN `runtime_user_id` VARCHAR(255) DEFAULT NULL COMMENT ''RuntimeContext userId'' AFTER `child_session_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'subject_type'), '', ', ADD COLUMN `subject_type` VARCHAR(32) DEFAULT NULL COMMENT ''可信调用主体类型'' AFTER `runtime_user_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'subject_id'), '', ', ADD COLUMN `subject_id` VARCHAR(128) DEFAULT NULL COMMENT ''可信调用主体ID或指纹'' AFTER `subject_type`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'subject_authenticated'), '', ', ADD COLUMN `subject_authenticated` TINYINT(1) DEFAULT NULL COMMENT ''主体是否已认证'' AFTER `subject_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'access_epoch'), '', ', ADD COLUMN `access_epoch` BIGINT DEFAULT NULL COMMENT ''租户访问版本快照'' AFTER `subject_authenticated`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND column_name = 'channel_code'), '', ', ADD COLUMN `channel_code` VARCHAR(32) DEFAULT NULL COMMENT ''服务端确认的调用渠道'' AFTER `access_epoch`')
);
SET @v87_column_ddl = IF(@v87_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_agent_task` ', SUBSTRING(@v87_columns, 3)));
PREPARE v87_column_stmt FROM @v87_column_ddl;
EXECUTE v87_column_stmt;
DEALLOCATE PREPARE v87_column_stmt;

SET @v87_indexes = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND index_name = 'idx_task_lease_recovery'), '', ', ADD INDEX `idx_task_lease_recovery` (`status`, `replayable`, `lease_until`, `attempt_count`)'),
    IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ai_agent_task' AND index_name = 'idx_task_owner_status'), '', ', ADD INDEX `idx_task_owner_status` (`owner_id`, `status`)')
);
SET @v87_index_ddl = IF(@v87_indexes = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_agent_task` ', SUBSTRING(@v87_indexes, 3)));
PREPARE v87_index_stmt FROM @v87_index_ddl;
EXECUTE v87_index_stmt;
DEALLOCATE PREPARE v87_index_stmt;

-- 升级前的本地 Supplier 无原始提示词，不能安全重放；只补租约，让扫描器在过期后确定性收敛为失败。
UPDATE `ai_agent_task`
SET `owner_id` = 'legacy-unowned',
    `lease_until` = CURRENT_TIMESTAMP(3),
    `heartbeat_at` = `updated_at`,
    `attempt_count` = 1,
    `replayable` = 0
WHERE `status` IN ('PENDING', 'RUNNING');
