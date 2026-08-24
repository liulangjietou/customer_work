-- Admin 调用日志同样冻结真实模型部署与价目；按列补齐，repair 后可安全重试。
SET @v85_table_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_agent_call_segment');
SET @v85_preflight_sql = IF(@v85_table_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v85_required_table_preflight_failed__`');
PREPARE v85_preflight_stmt FROM @v85_preflight_sql;
EXECUTE v85_preflight_stmt;
DEALLOCATE PREPARE v85_preflight_stmt;

SET @v85_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'provider'), '',
       ', ADD COLUMN `provider` VARCHAR(64) DEFAULT NULL COMMENT ''实际模型供应商'' AFTER `model_reported_ms`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'deployment_id'), '',
       ', ADD COLUMN `deployment_id` BIGINT DEFAULT NULL COMMENT ''实际模型部署ID'' AFTER `provider`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'model_name'), '',
       ', ADD COLUMN `model_name` VARCHAR(191) DEFAULT NULL COMMENT ''实际模型名'' AFTER `deployment_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'price_id'), '',
       ', ADD COLUMN `price_id` BIGINT DEFAULT NULL COMMENT ''调用时冻结的价目ID'' AFTER `model_name`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'currency'), '',
       ', ADD COLUMN `currency` VARCHAR(16) DEFAULT NULL COMMENT ''调用时冻结的币种'' AFTER `price_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'input_unit_price'), '',
       ', ADD COLUMN `input_unit_price` DECIMAL(20,8) DEFAULT NULL COMMENT ''调用时输入单价（每百万token）'' AFTER `currency`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'output_unit_price'), '',
       ', ADD COLUMN `output_unit_price` DECIMAL(20,8) DEFAULT NULL COMMENT ''调用时输出单价（每百万token）'' AFTER `input_unit_price`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'cached_unit_price'), '',
       ', ADD COLUMN `cached_unit_price` DECIMAL(20,8) DEFAULT NULL COMMENT ''调用时缓存输入单价（每百万token）'' AFTER `output_unit_price`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'pricing_status'), '',
       ', ADD COLUMN `pricing_status` VARCHAR(16) NOT NULL DEFAULT ''UNPRICED'' COMMENT ''PRICED/UNPRICED'' AFTER `cached_unit_price`')
);
SET @v85_ddl = IF(@v85_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_agent_call_segment` ', SUBSTRING(@v85_columns, 3)));
PREPARE v85_stmt FROM @v85_ddl;
EXECUTE v85_stmt;
DEALLOCATE PREPARE v85_stmt;
