-- 为 badcase 固化归一化问题哈希，使上线效果观察无需扫描或复制聊天正文。
SET @v21_badcase_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_badcase');
SET @v21_badcase_preflight_sql = IF(@v21_badcase_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_work_v21_cw_badcase_missing__`');
PREPARE v21_badcase_preflight_stmt FROM @v21_badcase_preflight_sql;
EXECUTE v21_badcase_preflight_stmt;
DEALLOCATE PREPARE v21_badcase_preflight_stmt;

SET @v21_signal_column_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cw_badcase' AND column_name = 'signal_hash'),
    'SELECT 1',
    'ALTER TABLE `cw_badcase` ADD COLUMN `signal_hash` CHAR(64) DEFAULT NULL COMMENT ''归一化用户问题SHA-256，供上线复发观测'' AFTER `agent_reply`');
PREPARE v21_signal_column_stmt FROM @v21_signal_column_sql;
EXECUTE v21_signal_column_stmt;
DEALLOCATE PREPARE v21_signal_column_stmt;

UPDATE `cw_badcase`
SET `signal_hash` = SHA2(LEFT(TRIM(`user_input`), 500), 256)
WHERE `signal_hash` IS NULL AND `user_input` IS NOT NULL AND TRIM(`user_input`) <> '';

SET @v21_signal_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cw_badcase'
      AND index_name = 'idx_badcase_signal'), 'SELECT 1',
    'ALTER TABLE `cw_badcase` ADD INDEX `idx_badcase_signal` (`tenant_id`, `signal_hash`, `created_at_ms`)');
PREPARE v21_signal_index_stmt FROM @v21_signal_index_sql;
EXECUTE v21_signal_index_stmt;
DEALLOCATE PREPARE v21_signal_index_stmt;
