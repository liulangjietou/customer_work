SET NAMES utf8mb4;

-- MCP 工具执行期主体授权。存量配置保留已认证调用方能力，匿名 IP 必须显式开放。
SET @v82_table_exists = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'ai_mcp'
);
SET @v82_preflight_sql = IF(@v82_table_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v82_required_table_preflight_failed__`');
PREPARE v82_preflight_stmt FROM @v82_preflight_sql;
EXECUTE v82_preflight_stmt;
DEALLOCATE PREPARE v82_preflight_stmt;

SET @v82_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_mcp' AND column_name = 'allowed_subject_types'
), 'SELECT 1',
   'ALTER TABLE `ai_mcp` ADD COLUMN `allowed_subject_types` VARCHAR(128) NOT NULL DEFAULT ''USER,ADMIN_USER,API_KEY'' COMMENT ''允许调用主体类型，逗号分隔：USER/ADMIN_USER/IP/API_KEY'' AFTER `test_time`');
PREPARE v82_stmt FROM @v82_ddl;
EXECUTE v82_stmt;
DEALLOCATE PREPARE v82_stmt;
