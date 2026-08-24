-- 模型调用按冻结价目结算，日账单改为汇总不可变金额事实，并增加归集串行锁。
SET NAMES utf8mb4;

SET @v95_segment_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_agent_call_segment');
SET @v95_log_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_agent_call_log');
SET @v95_usage_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_tenant_usage_daily');
SET @v95_preflight_sql = IF(@v95_segment_exists = 1 AND @v95_log_exists = 1 AND @v95_usage_exists = 1,
    'SELECT 1', 'SELECT * FROM `__customer_admin_v95_required_table_preflight_failed__`');
PREPARE v95_preflight_stmt FROM @v95_preflight_sql;
EXECUTE v95_preflight_stmt;
DEALLOCATE PREPARE v95_preflight_stmt;

SET @v95_segment_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'cost_amount'), '',
       ', ADD COLUMN `cost_amount` DECIMAL(30,14) DEFAULT NULL COMMENT ''按冻结价目结算的模型金额'' AFTER `pricing_status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'cost_currency'), '',
       ', ADD COLUMN `cost_currency` VARCHAR(16) DEFAULT NULL COMMENT ''结算币种'' AFTER `cost_amount`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_segment' AND column_name = 'cost_status'), '',
       ', ADD COLUMN `cost_status` VARCHAR(24) NOT NULL DEFAULT ''NOT_APPLICABLE'' COMMENT ''SETTLED/UNPRICED/USAGE_MISSING/USAGE_INVALID/NOT_APPLICABLE'' AFTER `cost_currency`')
);
SET @v95_segment_ddl = IF(@v95_segment_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_agent_call_segment` ', SUBSTRING(@v95_segment_columns, 3)));
PREPARE v95_segment_stmt FROM @v95_segment_ddl;
EXECUTE v95_segment_stmt;
DEALLOCATE PREPARE v95_segment_stmt;

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

SET @v95_log_columns = CONCAT(
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
SET @v95_log_ddl = IF(@v95_log_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_agent_call_log` ', SUBSTRING(@v95_log_columns, 3)));
PREPARE v95_log_stmt FROM @v95_log_ddl;
EXECUTE v95_log_stmt;
DEALLOCATE PREPARE v95_log_stmt;

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
    l.`model_cost_amount` = CASE WHEN c.settled_count > 0 AND c.currency_count = 1 THEN c.settled_amount ELSE NULL END,
    l.`model_cost_status` = CASE
        WHEN COALESCE(c.model_count, 0) = 0 THEN 'NO_MODEL'
        WHEN COALESCE(c.settled_count, 0) = 0 THEN 'UNAVAILABLE'
        WHEN c.currency_count > 1 THEN 'MULTI_CURRENCY'
        WHEN c.unsettled_count > 0 THEN 'PARTIAL'
        ELSE 'COMPLETE'
    END;

SET @v95_usage_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_tenant_usage_daily' AND column_name = 'model_segment_count'), '',
       ', ADD COLUMN `model_segment_count` BIGINT NOT NULL DEFAULT 0 COMMENT ''模型分段数'' AFTER `total_tokens`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_tenant_usage_daily' AND column_name = 'settled_segment_count'), '',
       ', ADD COLUMN `settled_segment_count` BIGINT NOT NULL DEFAULT 0 COMMENT ''已结算模型分段数'' AFTER `model_segment_count`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_tenant_usage_daily' AND column_name = 'unsettled_segment_count'), '',
       ', ADD COLUMN `unsettled_segment_count` BIGINT NOT NULL DEFAULT 0 COMMENT ''未结算模型分段数'' AFTER `settled_segment_count`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_tenant_usage_daily' AND column_name = 'source_max_call_log_id'), '',
       ', ADD COLUMN `source_max_call_log_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''本次归集冻结的客服端调用日志上界'' AFTER `currency`')
);
SET @v95_usage_ddl = IF(@v95_usage_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_tenant_usage_daily` ', SUBSTRING(@v95_usage_columns, 3)));
PREPARE v95_usage_stmt FROM @v95_usage_ddl;
EXECUTE v95_usage_stmt;
DEALLOCATE PREPARE v95_usage_stmt;

ALTER TABLE `cw_tenant_usage_daily`
    MODIFY COLUMN `amount` DECIMAL(30,14) NOT NULL DEFAULT 0 COMMENT '已结算模型金额（由调用事实精确求和）',
    MODIFY COLUMN `currency` VARCHAR(16) NOT NULL DEFAULT '' COMMENT '币种；不同币种禁止相加';

-- 旧日账单是按“当天最终价”二次估算，不能伪装成新结算事实；重跑对应日期后会被替换。
UPDATE `cw_tenant_usage_daily`
SET `model_segment_count` = GREATEST(`model_segment_count`, `call_count`),
    `settled_segment_count` = 0,
    `unsettled_segment_count` = GREATEST(`unsettled_segment_count`, `call_count`),
    `source_max_call_log_id` = 0
WHERE `source_max_call_log_id` = 0;

SET @v95_old_usage_unique = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cw_tenant_usage_daily'
      AND index_name = 'uk_tenant_usage_daily');
SET @v95_drop_usage_unique_sql = IF(@v95_old_usage_unique > 0,
    'ALTER TABLE `cw_tenant_usage_daily` DROP INDEX `uk_tenant_usage_daily`', 'SELECT 1');
PREPARE v95_drop_usage_unique_stmt FROM @v95_drop_usage_unique_sql;
EXECUTE v95_drop_usage_unique_stmt;
DEALLOCATE PREPARE v95_drop_usage_unique_stmt;

ALTER TABLE `cw_tenant_usage_daily`
    ADD UNIQUE INDEX `uk_tenant_usage_daily`
        (`tenant_id`, `stat_date`, `provider`, `model_name`, `currency`);

CREATE TABLE IF NOT EXISTS `cw_usage_aggregation_lock` (
    `stat_date` DATE NOT NULL PRIMARY KEY COMMENT '归集日期',
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后一次领取时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='日账单归集数据库串行锁';

SET @v95_has_cost_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log'
      AND index_name = 'idx_agent_call_cost_window');
SET @v95_has_tenant_column = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' AND column_name = 'tenant_id');
SET @v95_index_sql = IF(@v95_has_cost_index > 0, 'SELECT 1',
    IF(@v95_has_tenant_column > 0,
       'ALTER TABLE `cw_agent_call_log` ADD INDEX `idx_agent_call_cost_window` (`tenant_id`, `start_time`, `model_cost_status`)',
       'ALTER TABLE `cw_agent_call_log` ADD INDEX `idx_agent_call_cost_window` (`start_time`, `model_cost_status`)'));
PREPARE v95_index_stmt FROM @v95_index_sql;
EXECUTE v95_index_stmt;
DEALLOCATE PREPARE v95_index_stmt;
