-- 调用重放快照与独立执行权限。真实外部调用没有生产模式，只允许显式隔离环境的 DRY_RUN。
SET NAMES utf8mb4;

SET @v93_replay_table_count = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name IN ('cw_agent_call_log', 'sys_permission'));
SET @v93_replay_preflight_sql = IF(@v93_replay_table_count = 2, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v93_required_tables_missing__`');
PREPARE v93_replay_preflight_stmt FROM @v93_replay_preflight_sql;
EXECUTE v93_replay_preflight_stmt;
DEALLOCATE PREPARE v93_replay_preflight_stmt;

SET @v93_replay_column_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log'
      AND column_name = 'replay_snapshot_json'), 'SELECT 1',
    'ALTER TABLE `cw_agent_call_log` ADD COLUMN `replay_snapshot_json` JSON DEFAULT NULL COMMENT ''脱敏模型参数、RAG与工具重放事实'' AFTER `version_binding_json`');
PREPARE v93_replay_column_stmt FROM @v93_replay_column_sql;
EXECUTE v93_replay_column_stmt;
DEALLOCATE PREPARE v93_replay_column_stmt;

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 194, '隔离重放调用', 'agent-call-stats:replay', 2, 2
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'agent-call-stats:replay');
