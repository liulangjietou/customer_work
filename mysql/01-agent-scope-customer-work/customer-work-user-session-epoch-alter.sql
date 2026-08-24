-- 已有 customer_work 库升级：增加终端用户会话撤销版本。
SET @cw_user_session_epoch_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cw_user'
      AND COLUMN_NAME = 'session_epoch'
);

SET @cw_user_session_epoch_ddl = IF(
    @cw_user_session_epoch_exists = 0,
    'ALTER TABLE `cw_user` ADD COLUMN `session_epoch` BIGINT NOT NULL DEFAULT 0 COMMENT ''用户会话撤销版本'' AFTER `level_code`',
    'SELECT 1'
);

PREPARE cw_user_session_epoch_stmt FROM @cw_user_session_epoch_ddl;
EXECUTE cw_user_session_epoch_stmt;
DEALLOCATE PREPARE cw_user_session_epoch_stmt;
