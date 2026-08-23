SET NAMES utf8mb4;

-- 金额预算告警事实：归集提交后按自然日/月检测，业务唯一键保证回补归集不会重复告警。
CREATE TABLE IF NOT EXISTS `ai_cost_alert` (
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`        VARCHAR(64) NOT NULL COMMENT '租户ID',
    `period`           VARCHAR(16) NOT NULL COMMENT '周期：DAILY/MONTHLY',
    `period_key`       VARCHAR(16) NOT NULL COMMENT '自然周期键，如 2026-08 或 2026-08-21',
    `alert_type`       VARCHAR(32) NOT NULL COMMENT 'BUDGET_WARNING/BUDGET_EXCEEDED/FORECAST_EXCEEDED',
    `used_amount`      DECIMAL(16,4) NOT NULL DEFAULT 0 COMMENT '首次触发时已结算金额（元）',
    `limit_amount`     DECIMAL(16,4) NOT NULL DEFAULT 0 COMMENT '触发时金额预算（元）',
    `forecast_amount`  DECIMAL(16,4) NOT NULL DEFAULT 0 COMMENT '触发时周期预测金额（元）',
    `status`           VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKED',
    `first_seen_at`    DATETIME(3) NOT NULL COMMENT '首次触发时间',
    `ack_by`           BIGINT COMMENT '确认人ID',
    `ack_at`           DATETIME(3) COMMENT '确认时间',
    `create_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY `uk_cost_alert_business` (`tenant_id`, `period`, `period_key`, `alert_type`),
    KEY `idx_cost_alert_tenant_status` (`tenant_id`, `status`, `first_seen_at`),
    KEY `idx_cost_alert_status_time` (`status`, `first_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='金额预算告警事实';

-- 导出与手工归集职责拆开：billing:export 只控制文件导出，补数操作单列控制面权限。
INSERT INTO `sys_permission`
    (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
VALUES
    (248, 224, '手工归集', 'billing:aggregate', 2, 4);
