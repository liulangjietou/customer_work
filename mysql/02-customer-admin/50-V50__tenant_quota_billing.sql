-- ============================================================================
-- B3 配额与计费：模型单价表 + 租户配额 + 用量归集
--
-- 成本治理此前只能回答"花了多少 token"，这批补上"谁花的、值多少钱、超了怎么办"。
-- 本迁移建两张 admin 库的表：
--   ai_model_price         单价（token → 金额的换算依据，按生效时间留历史）
--   cw_tenant_usage_daily  日用量归集（账单与报表的数据源，由定时任务从调用日志汇总）
--
-- 配额表 cw_tenant_quota **不在这里**：它要被客服端运行时读取（拦在模型调用之前），
-- 按内容风控三表的先例落在客服端库、由 starter 定义 Mapper，admin 复用同一套 Mapper 管理，
-- 见 customer-work-schema.sql。跨库放两份或让客服端反查 admin 库都不如沿用既有模式。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ai_model_price` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `provider`        VARCHAR(64) NOT NULL COMMENT '模型厂商，如 dashscope/openai',
    `model_name`      VARCHAR(128) NOT NULL COMMENT '模型名，如 qwen-max',
    -- 单价用「每百万 token」而非「每 token」：后者小数位太多，DECIMAL 精度与可读性都难兼顾，
    -- 且各厂商官网报价本身就是按百万 token 计的，照抄不用换算，少一层出错机会
    `input_price`     DECIMAL(16,6) NOT NULL DEFAULT 0 COMMENT '输入单价（元/百万 token）',
    `output_price`    DECIMAL(16,6) NOT NULL DEFAULT 0 COMMENT '输出单价（元/百万 token）',
    -- 命中缓存的输入 token 通常按折扣价计，单独一列而不是按比例算：折扣比例各厂商不同且会变
    `cached_price`    DECIMAL(16,6) NOT NULL DEFAULT 0 COMMENT '缓存命中输入单价（元/百万 token）',
    `currency`        VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `effective_from`  DATETIME NOT NULL COMMENT '生效时间；调价不改旧行而是插新行，历史账单才算得回去',
    `remark`          VARCHAR(255) COMMENT '备注',
    `create_by`       BIGINT COMMENT '创建人ID',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT COMMENT '更新人ID',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    KEY `idx_model_price_lookup` (`provider`, `model_name`, `effective_from`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型单价（按生效时间留历史，供账单回溯）';

-- 单价是平台统一定义的（租户不该也不能自己定价），故不带 tenant_id，进拦截器忽略清单

CREATE TABLE IF NOT EXISTS `cw_tenant_usage_daily` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`       VARCHAR(64) NOT NULL COMMENT '租户ID',
    `stat_date`       DATE NOT NULL COMMENT '统计日期（自然日）',
    `provider`        VARCHAR(64) NOT NULL DEFAULT '' COMMENT '模型厂商',
    `model_name`      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '模型名',
    `call_count`      BIGINT NOT NULL DEFAULT 0 COMMENT '调用次数',
    `input_tokens`    BIGINT NOT NULL DEFAULT 0 COMMENT '输入 token',
    `output_tokens`   BIGINT NOT NULL DEFAULT 0 COMMENT '输出 token',
    `cached_tokens`   BIGINT NOT NULL DEFAULT 0 COMMENT '缓存命中输入 token（input 的子集）',
    `total_tokens`    BIGINT NOT NULL DEFAULT 0 COMMENT '总 token',
    -- 金额在归集时按当日单价算好落库，而不是查询时实时算：
    -- 单价会变，实时算会让历史账单随调价而变动，对不上已出账的数
    `amount`          DECIMAL(16,4) NOT NULL DEFAULT 0 COMMENT '金额（元，按归集时点的单价结算）',
    `currency`        VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_tenant_usage_daily` (`tenant_id`, `stat_date`, `provider`, `model_name`),
    KEY `idx_usage_daily_date` (`stat_date`),
    KEY `idx_usage_daily_tenant` (`tenant_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户日用量归集（账单与报表数据源）';

-- ============================================================================
-- 配额与计费菜单权限点（id 从 224 起，223 是 V49 租户管理占用的最后一个）
--
-- 与 tenant: 前缀同理走 billing: 前缀：TenantProvisionService 排除的是 tenant:，
-- 计费同属平台专属，这里显式列出以便将来一并纳入排除规则。
-- ============================================================================

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (224, 1, '配额与计费', 'billing:view', 1, '/system/billing', 'Wallet', 'library', 10);

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (225, 224, '编辑配额', 'billing:quota-edit', 2, 1),
    (226, 224, '编辑单价', 'billing:price-edit', 2, 2),
    (227, 224, '导出账单', 'billing:export', 2, 3);

-- 演示单价种子：与项目默认模型对齐（数值为示意，上线前按厂商实际报价维护）
INSERT INTO `ai_model_price` (`provider`, `model_name`, `input_price`, `output_price`, `cached_price`, `currency`, `effective_from`, `remark`)
SELECT 'dashscope', 'qwen-max', 2.400000, 9.600000, 0.480000, 'CNY', '2026-01-01 00:00:00', '示意价格，上线前按厂商实际报价维护'
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_price` WHERE `provider` = 'dashscope' AND `model_name` = 'qwen-max');
