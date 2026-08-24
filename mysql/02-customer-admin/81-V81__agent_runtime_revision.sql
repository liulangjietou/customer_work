SET NAMES utf8mb4;

-- Admin 多 Pod 智能体实例缓存修订号：影响运行时装配的写操作原子递增，读取侧命中前校验。
SET @v81_table_exists = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'ai_agent'
);
SET @v81_preflight_sql = IF(@v81_table_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v81_required_table_preflight_failed__`');
PREPARE v81_preflight_stmt FROM @v81_preflight_sql;
EXECUTE v81_preflight_stmt;
DEALLOCATE PREPARE v81_preflight_stmt;

SET @v81_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent' AND column_name = 'runtime_revision'
), 'SELECT 1',
   'ALTER TABLE `ai_agent` ADD COLUMN `runtime_revision` BIGINT NOT NULL DEFAULT 0 COMMENT ''运行时实例配置修订号'' AFTER `status`');
PREPARE v81_stmt FROM @v81_ddl;
EXECUTE v81_stmt;
DEALLOCATE PREPARE v81_stmt;
