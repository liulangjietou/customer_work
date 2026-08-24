SET NAMES utf8mb4;

-- Admin/Harness 记忆按可信调用主体分区后，agent_code 存储“租户智能体键 + 主体摘要”。
-- MySQL DDL 不可事务回滚：先校验目标表存在，再按当前列长度生成 ALTER，确保 repair 后可安全重试。
SET @v83_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_type = 'BASE TABLE'
      AND table_name = 'ai_agent_memory'
);
SET @v83_preflight_sql = IF(
    @v83_table_exists = 1,
    'SELECT 1',
    'SELECT * FROM `__customer_admin_v83_required_table_preflight_failed__`'
);
PREPARE v83_preflight_stmt FROM @v83_preflight_sql;
EXECUTE v83_preflight_stmt;
DEALLOCATE PREPARE v83_preflight_stmt;

SET @v83_agent_code_length = (
    SELECT character_maximum_length
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_agent_memory'
      AND column_name = 'agent_code'
);
SET @v83_ddl = IF(
    @v83_agent_code_length >= 191,
    'SELECT 1',
    'ALTER TABLE `ai_agent_memory` MODIFY COLUMN `agent_code` VARCHAR(191) NOT NULL COMMENT ''智能体与可信调用主体分区键'''
);
PREPARE v83_stmt FROM @v83_ddl;
EXECUTE v83_stmt;
DEALLOCATE PREPARE v83_stmt;
