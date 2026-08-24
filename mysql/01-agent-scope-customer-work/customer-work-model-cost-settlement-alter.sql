-- 已有 customer_work 库升级：按冻结价目结算每个模型调用，并汇总调用成本。
SET @v20_segment_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_agent_call_segment');
SET @v20_log_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_agent_call_log');
SET @v20_preflight_sql = IF(@v20_segment_exists = 1 AND @v20_log_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_work_v20_required_table_preflight_failed__`');
PREPARE v20_preflight_stmt FROM @v20_preflight_sql;
EXECUTE v20_preflight_stmt;
DEALLOCATE PREPARE v20_preflight_stmt;

SET @v20_segment_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'cost_amount'), '',
       ', ADD COLUMN `cost_amount` DECIMAL(30,14) DEFAULT NULL COMMENT ''按冻结价目结算的模型金额'' AFTER `pricing_status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'cost_currency'), '',
       ', ADD COLUMN `cost_currency` VARCHAR(16) DEFAULT NULL COMMENT ''结算币种'' AFTER `cost_amount`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'cost_status'), '',
       ', ADD COLUMN `cost_status` VARCHAR(24) NOT NULL DEFAULT ''NOT_APPLICABLE'' COMMENT ''SETTLED/UNPRICED/USAGE_MISSING/USAGE_INVALID/NOT_APPLICABLE'' AFTER `cost_currency`')
);
SET @v20_segment_ddl = IF(@v20_segment_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_agent_call_segment` ', SUBSTRING(@v20_segment_columns, 3)));
PREPARE v20_segment_stmt FROM @v20_segment_ddl;
EXECUTE v20_segment_stmt;
DEALLOCATE PREPARE v20_segment_stmt;

UPDATE `cw_agent_call_segment`
SET `cost_currency` = CASE WHEN `kind` = 'MODEL' THEN NULLIF(TRIM(`currency`), '') ELSE NULL END,
    `cost_amount` = CASE
        WHEN `kind` = 'MODEL'
         AND `pricing_status` = 'PRICED'
         AND NULLIF(TRIM(`currency`), '') IS NOT NULL
         AND `input_tokens` IS NOT NULL AND `output_tokens` IS NOT NULL
         AND `input_tokens` >= 0 AND `output_tokens` >= 0
         AND COALESCE(`cached_tokens`, 0) >= 0
         AND COALESCE(`cached_tokens`, 0) <= `input_tokens`
         AND `input_unit_price` IS NOT NULL AND `input_unit_price` >= 0
         AND `output_unit_price` IS NOT NULL AND `output_unit_price` >= 0
         AND (COALESCE(`cached_tokens`, 0) = 0
              OR (`cached_unit_price` IS NOT NULL AND `cached_unit_price` >= 0))
        THEN CAST((
            (`input_tokens` - COALESCE(`cached_tokens`, 0)) * `input_unit_price`
            + `output_tokens` * `output_unit_price`
            + COALESCE(`cached_tokens`, 0) * COALESCE(`cached_unit_price`, 0)
        ) / 1000000 AS DECIMAL(30,14))
        ELSE NULL
    END,
    `cost_status` = CASE
        WHEN `kind` <> 'MODEL' THEN 'NOT_APPLICABLE'
        WHEN `pricing_status` <> 'PRICED'
          OR NULLIF(TRIM(`currency`), '') IS NULL
          OR `input_unit_price` IS NULL OR `input_unit_price` < 0
          OR `output_unit_price` IS NULL OR `output_unit_price` < 0
          OR (COALESCE(`cached_tokens`, 0) > 0
              AND (`cached_unit_price` IS NULL OR `cached_unit_price` < 0)) THEN 'UNPRICED'
        WHEN `input_tokens` IS NULL OR `output_tokens` IS NULL THEN 'USAGE_MISSING'
        WHEN `input_tokens` < 0 OR `output_tokens` < 0
          OR COALESCE(`cached_tokens`, 0) < 0
          OR COALESCE(`cached_tokens`, 0) > `input_tokens` THEN 'USAGE_INVALID'
        ELSE 'SETTLED'
    END;

SET @v20_log_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'model_cost_amount'), '',
       ', ADD COLUMN `model_cost_amount` DECIMAL(30,14) DEFAULT NULL COMMENT ''本次调用已结算模型金额'' AFTER `model_reported_ms`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'model_cost_currency'), '',
       ', ADD COLUMN `model_cost_currency` VARCHAR(16) DEFAULT NULL COMMENT ''单币种结算币种'' AFTER `model_cost_amount`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'model_cost_status'), '',
       ', ADD COLUMN `model_cost_status` VARCHAR(24) NOT NULL DEFAULT ''NO_MODEL'' COMMENT ''COMPLETE/PARTIAL/UNAVAILABLE/MULTI_CURRENCY/NO_MODEL'' AFTER `model_cost_currency`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'model_segment_count'), '',
       ', ADD COLUMN `model_segment_count` INT NOT NULL DEFAULT 0 COMMENT ''模型分段数'' AFTER `model_cost_status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'settled_cost_segment_count'), '',
       ', ADD COLUMN `settled_cost_segment_count` INT NOT NULL DEFAULT 0 COMMENT ''已结算模型分段数'' AFTER `model_segment_count`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'unsettled_cost_segment_count'), '',
       ', ADD COLUMN `unsettled_cost_segment_count` INT NOT NULL DEFAULT 0 COMMENT ''未结算模型分段数'' AFTER `settled_cost_segment_count`')
);
SET @v20_log_ddl = IF(@v20_log_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_agent_call_log` ', SUBSTRING(@v20_log_columns, 3)));
PREPARE v20_log_stmt FROM @v20_log_ddl;
EXECUTE v20_log_stmt;
DEALLOCATE PREPARE v20_log_stmt;

UPDATE `cw_agent_call_log` l
LEFT JOIN (
    SELECT `call_log_id`,
           COUNT(*) AS model_count,
           SUM(CASE WHEN `cost_status` = 'SETTLED' THEN 1 ELSE 0 END) AS settled_count,
           SUM(CASE WHEN `cost_status` <> 'SETTLED' THEN 1 ELSE 0 END) AS unsettled_count,
           COUNT(DISTINCT CASE WHEN `cost_status` = 'SETTLED' THEN `cost_currency` END) AS currency_count,
           MAX(CASE WHEN `cost_status` = 'SETTLED' THEN `cost_currency` END) AS single_currency,
           SUM(CASE WHEN `cost_status` = 'SETTLED' THEN `cost_amount` ELSE 0 END) AS settled_amount
    FROM `cw_agent_call_segment`
    WHERE `kind` = 'MODEL'
    GROUP BY `call_log_id`
) c ON c.`call_log_id` = l.`id`
SET l.`model_segment_count` = COALESCE(c.model_count, 0),
    l.`settled_cost_segment_count` = COALESCE(c.settled_count, 0),
    l.`unsettled_cost_segment_count` = COALESCE(c.unsettled_count, 0),
    l.`model_cost_currency` = CASE WHEN c.currency_count = 1 THEN c.single_currency ELSE NULL END,
    l.`model_cost_amount` = CASE
        WHEN c.settled_count > 0 AND c.currency_count = 1 THEN c.settled_amount
        ELSE NULL
    END,
    l.`model_cost_status` = CASE
        WHEN COALESCE(c.model_count, 0) = 0 THEN 'NO_MODEL'
        WHEN COALESCE(c.settled_count, 0) = 0 THEN 'UNAVAILABLE'
        WHEN c.currency_count > 1 THEN 'MULTI_CURRENCY'
        WHEN c.unsettled_count > 0 THEN 'PARTIAL'
        ELSE 'COMPLETE'
    END;

SET @v20_has_cost_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log'
      AND index_name = 'idx_agent_call_cost_window');
SET @v20_has_tenant_column = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'tenant_id');
SET @v20_index_sql = IF(@v20_has_cost_index > 0, 'SELECT 1',
    IF(@v20_has_tenant_column > 0,
       'ALTER TABLE `cw_agent_call_log` ADD INDEX `idx_agent_call_cost_window` (`tenant_id`, `start_time`, `model_cost_status`)',
       'ALTER TABLE `cw_agent_call_log` ADD INDEX `idx_agent_call_cost_window` (`start_time`, `model_cost_status`)'));
PREPARE v20_index_stmt FROM @v20_index_sql;
EXECUTE v20_index_stmt;
DEALLOCATE PREPARE v20_index_stmt;
