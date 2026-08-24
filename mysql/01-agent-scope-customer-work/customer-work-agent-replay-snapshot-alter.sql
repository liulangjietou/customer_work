-- 重放快照只保存非密钥模型参数、RAG/工具摘要与哈希，不复制原始高敏正文。
SET @v19_replay_table_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log');
SET @v19_replay_preflight_sql = IF(@v19_replay_table_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_work_v19_cw_agent_call_log_missing__`');
PREPARE v19_replay_preflight_stmt FROM @v19_replay_preflight_sql;
EXECUTE v19_replay_preflight_stmt;
DEALLOCATE PREPARE v19_replay_preflight_stmt;

SET @v19_replay_column_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log'
      AND column_name = 'replay_snapshot_json'), 'SELECT 1',
    'ALTER TABLE `cw_agent_call_log` ADD COLUMN `replay_snapshot_json` JSON DEFAULT NULL COMMENT ''脱敏模型参数、RAG与工具重放事实'' AFTER `version_binding_json`');
PREPARE v19_replay_column_stmt FROM @v19_replay_column_sql;
EXECUTE v19_replay_column_stmt;
DEALLOCATE PREPARE v19_replay_column_stmt;
