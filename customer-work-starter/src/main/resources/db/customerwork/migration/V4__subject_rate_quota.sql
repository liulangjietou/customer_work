-- ============================================================================
-- 主体级速率配额：每个用户 / 每个匿名 IP / 每把 API Key 在滚动窗口内的 token 量与请求次数上限。
--
-- 与 cw_tenant_quota 是两件事，刻意分表而不是加个维度列：
--   · 租户配额  —— 自然日/月对齐，要跟账单对得上，管的是"这个客户这个月能花多少钱"；
--   · 主体配额  —— 最近 N 秒滚动，跟账单无关，管的是"这个调用者这半小时能用多少"。
-- 周期语义、判定时机、超限处置都不同，合表只会让两套逻辑互相牵制。
-- ============================================================================

-- 等级定义：一档额度的完整描述，租户内 level_code 唯一
CREATE TABLE IF NOT EXISTS `cw_subject_quota_level` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `level_code`     VARCHAR(64) NOT NULL COMMENT '等级编码，如 free/vip/anonymous',
    `level_name`     VARCHAR(128) NOT NULL COMMENT '等级名称（运营可读）',
    `subject_type`   VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT '适用主体: USER 登录用户 / IP 匿名 / API_KEY 接入方',
    `window_seconds` INT NOT NULL DEFAULT 1800 COMMENT '滚动窗口长度（秒），1800=30分钟',
    `token_limit`    BIGINT NOT NULL DEFAULT 0 COMMENT '窗口内 token 上限，0=不限',
    `request_limit`  INT NOT NULL DEFAULT 0 COMMENT '窗口内请求次数上限，0=不限',
    `exceed_action`  VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT '超限处置: BLOCK 拦截 / WARN 仅记录',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `remark`         VARCHAR(255) COMMENT '备注',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_squota_level` (`tenant_id`, `level_code`),
    INDEX `idx_squota_level_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主体配额等级（每租户每档一条）';

-- 超限命中记录：只在真的触顶那一刻写一条，正常流量不产生任何写入。
-- 落的是"命中"而不是"实时余额"：余额在计数器里（进程内或 Redis），后台是另一个进程，
-- 读它要么被迫依赖 Redis 模式、要么读到只属于某个副本的数；而运营真正要回答的是"谁在刷、哪档配紧了"。
CREATE TABLE IF NOT EXISTS `cw_subject_quota_hit` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `subject_type`   VARCHAR(32) NOT NULL COMMENT '主体类型: USER/IP/API_KEY',
    `subject_id`     VARCHAR(128) NOT NULL COMMENT '主体标识（API Key 已做 SHA-256 指纹，不含明文）',
    `level_code`     VARCHAR(64) COMMENT '判定所依据的等级',
    `limit_kind`     VARCHAR(16) NOT NULL COMMENT '触顶维度: TOKEN/REQUEST',
    `used`           BIGINT NOT NULL DEFAULT 0 COMMENT '触顶时已用量',
    `limit_value`    BIGINT NOT NULL DEFAULT 0 COMMENT '触顶时的上限',
    `window_seconds` INT NOT NULL DEFAULT 0 COMMENT '滚动窗口长度（秒）',
    `action`         VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT '当时处置: BLOCK 真拦了 / WARN 只记录',
    `resource`       VARCHAR(255) COMMENT '触发位置（HTTP 路径或 ws:chat）',
    `created_at_ms`  BIGINT NOT NULL COMMENT '命中时刻（毫秒）',
    INDEX `idx_squota_hit_tenant_time` (`tenant_id`, `created_at_ms`),
    INDEX `idx_squota_hit_subject` (`tenant_id`, `subject_type`, `subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主体配额超限命中记录';

-- 用户等级绑定落在账户表上，不另建映射表：等级是"这个账户能用多少"的自身属性，
-- 与启停、头像同类；拆出去只会让每次限流判定多一次跨表查询。空值 = 走配置里的默认档。
ALTER TABLE `cw_user` ADD COLUMN `level_code` VARCHAR(64) DEFAULT NULL COMMENT '配额等级编码（空=默认档）';

-- 出厂五档种子（仅默认租户）。功能默认关闭，故种子不改变任何现有行为，
-- 但让后台一打开就有东西可改——空表加一份看不见的"内置档"，运营只会问"到底哪个在生效"。
-- free/anonymous/api-key 三档的数值与 SubjectQuotaProperties 的内置档保持一致，两处不能漂移。
-- 次数上限看着宽是因为口径含查询请求（默认覆盖整个 /api/customer/user/ 面），
-- 真正卡成本的是 token 那一维——它只在模型调用后累加。
INSERT INTO `cw_subject_quota_level`
    (`tenant_id`, `level_code`, `level_name`, `subject_type`, `window_seconds`,
     `token_limit`, `request_limit`, `exceed_action`, `enabled`, `remark`,
     `created_at_ms`, `updated_at_ms`)
VALUES
    ('default', 'free',      '免费用户', 'USER',    1800,   50000,  100, 'BLOCK', 1, '注册用户默认档', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'vip',       'VIP用户',  'USER',    1800,  200000,  300, 'BLOCK', 1, '付费用户',       UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'svip',      'SVIP用户', 'USER',    1800, 1000000, 1000, 'BLOCK', 1, '高级付费用户',   UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'anonymous', '匿名访客', 'IP',      1800,   10000,   20, 'BLOCK', 1, '未登录，按来源IP计', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'api-key',   '接入方',   'API_KEY', 3600, 1000000, 2000, 'BLOCK', 1, '服务端接入，按Key指纹计', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);
