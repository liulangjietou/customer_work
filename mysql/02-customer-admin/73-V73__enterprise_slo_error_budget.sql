-- 企业级 SLO/error-budget：租户策略、短/长窗口 burn-rate、幂等告警事实。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_slo_policy` (
    `id`                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`              VARCHAR(64) NOT NULL COMMENT '租户ID',
    `policy_name`            VARCHAR(128) NOT NULL COMMENT '策略名称',
    `scope_type`             VARCHAR(16) NOT NULL COMMENT 'TENANT/AGENT/CHANNEL',
    `scope_key`              VARCHAR(128) DEFAULT NULL COMMENT 'Agent编码或渠道编码；租户范围为空',
    `availability_target`    DECIMAL(8,7) NOT NULL COMMENT '成功率目标0..1',
    `latency_target`         DECIMAL(8,7) NOT NULL COMMENT '阈值内完成比例目标0..1',
    `latency_threshold_ms`   BIGINT NOT NULL COMMENT '延迟阈值毫秒',
    `short_window_minutes`   INT NOT NULL COMMENT '短窗口分钟',
    `long_window_minutes`    INT NOT NULL COMMENT '长窗口分钟',
    `burn_rate_threshold`    DECIMAL(12,6) NOT NULL COMMENT '短长窗口共同触发的燃烧率阈值',
    `enabled`                TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `create_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_slo_policy_tenant_name` (`tenant_id`, `policy_name`),
    KEY `idx_slo_policy_tenant_scope` (`tenant_id`, `scope_type`, `scope_key`, `enabled`),
    CONSTRAINT `chk_slo_policy_scope` CHECK (`scope_type` IN ('TENANT', 'AGENT', 'CHANNEL')),
    CONSTRAINT `chk_slo_policy_availability` CHECK (`availability_target` > 0 AND `availability_target` < 1),
    CONSTRAINT `chk_slo_policy_latency` CHECK (`latency_target` > 0 AND `latency_target` < 1),
    CONSTRAINT `chk_slo_policy_latency_ms` CHECK (`latency_threshold_ms` > 0),
    CONSTRAINT `chk_slo_policy_windows` CHECK (`short_window_minutes` > 0 AND `long_window_minutes` > `short_window_minutes`),
    CONSTRAINT `chk_slo_policy_burn` CHECK (`burn_rate_threshold` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户SLO策略';

CREATE TABLE IF NOT EXISTS `ai_slo_alert` (
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`           VARCHAR(64) NOT NULL COMMENT '租户ID',
    `policy_id`           BIGINT NOT NULL COMMENT 'SLO策略ID',
    `window_end_minute`   BIGINT NOT NULL COMMENT '评估时刻UTC分钟桶',
    `alert_type`          VARCHAR(32) NOT NULL COMMENT 'MULTI_WINDOW_BURN',
    `short_burn_rate`     DECIMAL(12,6) NOT NULL COMMENT '短窗口燃烧率',
    `long_burn_rate`      DECIMAL(12,6) NOT NULL COMMENT '长窗口燃烧率',
    `first_seen_at`       DATETIME NOT NULL COMMENT '首次发现时间',
    UNIQUE KEY `uk_slo_alert_fact` (`tenant_id`, `policy_id`, `window_end_minute`, `alert_type`),
    KEY `idx_slo_alert_tenant_time` (`tenant_id`, `first_seen_at` DESC),
    CONSTRAINT `chk_slo_alert_type` CHECK (`alert_type` IN ('MULTI_WINDOW_BURN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO错误预算告警事实';

-- 菜单与按钮权限均使用业务键幂等插入，避免和并行迁移争抢固定 ID。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`)
SELECT 1, 'SLO 错误预算', 'slo:view', 1, '/system/slo', 'DataAnalysis', 'library', 9
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'slo:view');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT p.id, '编辑 SLO 策略', 'slo:edit', 2, 1
FROM `sys_permission` p
WHERE p.perm_code = 'slo:view'
  AND NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'slo:edit');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT p.id, '评估错误预算', 'slo:evaluate', 2, 2
FROM `sys_permission` p
WHERE p.perm_code = 'slo:view'
  AND NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'slo:evaluate');
