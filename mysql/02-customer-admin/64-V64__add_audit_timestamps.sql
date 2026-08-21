SET NAMES utf8mb4;

-- 为同时缺少创建时间与修改时间的存量表补齐数据库级审计时间。
-- MySQL DDL 不可事务回滚：先确认九张目标表全部存在，再按实际缺列生成 ALTER，确保 repair 后可安全重试。

SET @v64_table_count = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_type = 'BASE TABLE'
      AND table_name IN (
          'sys_user_role',
          'sys_role_permission',
          'ai_agent_mcp',
          'ai_agent_skill',
          'ai_agent_sub_agent',
          'ai_agent_system_tool',
          'ai_agent_knowledge_base',
          'ai_scheduled_task_run',
          'cw_agent_call_segment'
      )
);
SET @v64_preflight_sql = IF(
    @v64_table_count = 9,
    'SELECT 1',
    'SELECT * FROM `__customer_admin_v64_required_table_preflight_failed__`'
);
PREPARE v64_preflight_stmt FROM @v64_preflight_sql;
EXECUTE v64_preflight_stmt;
DEALLOCATE PREPARE v64_preflight_stmt;

-- target: sys_user_role
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user_role' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user_role' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `sys_user_role` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: sys_role_permission
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_role_permission' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_role_permission' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `sys_role_permission` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: ai_agent_mcp
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_mcp' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_mcp' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `ai_agent_mcp` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: ai_agent_skill
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_skill' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_skill' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `ai_agent_skill` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: ai_agent_sub_agent
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_sub_agent' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_sub_agent' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `ai_agent_sub_agent` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: ai_agent_system_tool
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_system_tool' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_system_tool' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `ai_agent_system_tool` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: ai_agent_knowledge_base
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_knowledge_base' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_agent_knowledge_base' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `ai_agent_knowledge_base` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: ai_scheduled_task_run
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_scheduled_task_run' AND column_name = 'create_time'), '', 'ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_scheduled_task_run' AND column_name = 'update_time'), '', 'ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `ai_scheduled_task_run` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;

-- target: cw_agent_call_segment
SET @v64_created_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'created_at'), '', 'ADD COLUMN `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间''');
SET @v64_updated_clause = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'updated_at'), '', 'ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''修改时间''');
SET @v64_columns = CONCAT_WS(', ', NULLIF(@v64_created_clause, ''), NULLIF(@v64_updated_clause, ''));
SET @v64_ddl = IF(@v64_columns = '', 'SELECT 1', CONCAT('ALTER TABLE `cw_agent_call_segment` ', @v64_columns));
PREPARE v64_stmt FROM @v64_ddl;
EXECUTE v64_stmt;
DEALLOCATE PREPARE v64_stmt;
