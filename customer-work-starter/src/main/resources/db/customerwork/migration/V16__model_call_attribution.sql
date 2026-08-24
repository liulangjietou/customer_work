-- 每个 MODEL 分段冻结实际供应商、部署、模型与调用时价目；缺价显式 UNPRICED。
SET @v16_table_exists = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
      AND table_name = 'cw_agent_call_segment'
);
SET @v16_preflight_sql = IF(@v16_table_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_work_v16_required_table_preflight_failed__`');
PREPARE v16_preflight_stmt FROM @v16_preflight_sql;
EXECUTE v16_preflight_stmt;
DEALLOCATE PREPARE v16_preflight_stmt;

SET @v16_columns = CONCAT(
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
SET @v16_ddl = IF(@v16_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_agent_call_segment` ', SUBSTRING(@v16_columns, 3)));
PREPARE v16_stmt FROM @v16_ddl;
EXECUTE v16_stmt;
DEALLOCATE PREPARE v16_stmt;
