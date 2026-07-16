-- 首行强制 utf8mb4，避免走 stdin 管道时客户端字符集回退 latin1 把中文 COMMENT 字节级写坏（见 mysql/README.md）
SET NAMES utf8mb4;

-- ============================================================================
-- 增量迁移：cw_ticket 增加 last_user_active_at_ms（用户最后活跃时间戳，空闲超时巡检基准）
-- 适用场景：已部署的 agent_scope_customer_work 库增量升级（全新建库直接跑 customer-work-schema.sql 即含此列）。
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，改用 information_schema 判定，列已存在则跳过。
-- ============================================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cw_ticket'
      AND COLUMN_NAME = 'last_user_active_at_ms'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `cw_ticket` ADD COLUMN `last_user_active_at_ms` BIGINT DEFAULT 0 COMMENT ''用户最后活跃时间戳（毫秒，空闲超时巡检基准）'' AFTER `closed_at_ms`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史行回填：新增列默认 0，用 updated_at_ms 兜底，避免空闲巡检把老工单立刻误关
UPDATE `cw_ticket`
SET `last_user_active_at_ms` = `updated_at_ms`
WHERE `last_user_active_at_ms` IS NULL OR `last_user_active_at_ms` = 0;
