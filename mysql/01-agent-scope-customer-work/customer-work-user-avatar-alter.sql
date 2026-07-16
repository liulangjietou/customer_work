-- 首行强制 utf8mb4，避免走 stdin 管道时客户端字符集回退 latin1 把中文 COMMENT 字节级写坏（见 mysql/README.md）
SET NAMES utf8mb4;

-- ============================================================================
-- 增量迁移：cw_user 增加 avatar_url（用户头像访问 URL，头像上传功能）
-- 适用场景：已部署的 agent_scope_customer_work 库增量升级（全新建库直接跑 customer-work-schema.sql 即含此列）。
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，改用 information_schema 判定，列已存在则跳过。
-- ============================================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cw_user'
      AND COLUMN_NAME = 'avatar_url'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `cw_user` ADD COLUMN `avatar_url` VARCHAR(255) COMMENT ''头像访问URL（相对路径，可为空）'' AFTER `created_at_ms`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
