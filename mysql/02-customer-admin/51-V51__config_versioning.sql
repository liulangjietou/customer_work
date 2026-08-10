-- ============================================================================
-- B4 配置版本化与灰度：发布前留快照，出问题能回退
--
-- 此前 CustomerWorkConfigPublisher 是直接覆盖 Nacos 上的当前配置，发布前只做连通性探测——
-- 探测通过不代表配置本身是对的，改坏了 prompt 就只能靠人肉记忆改回去。
--
-- 本表存的是"每次发布下发了什么"的完整快照。回滚不是删掉新版本，而是把旧版本的内容
-- 作为一个新版本再发一次——发布历史因此永远是只增的，任何时刻都能回答"当时线上是哪一版"。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ai_config_version` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `config_type`   VARCHAR(32) NOT NULL COMMENT '配置类型：AGENT 智能体运行时配置 / MODEL 模型配置',
    `target_code`   VARCHAR(128) NOT NULL COMMENT '目标业务编码（如 agentCode / channelCode），人可读、跨环境稳定',
    `target_id`     BIGINT COMMENT '目标主键（可空：跨环境迁移后主键会变，故以 target_code 为准）',
    -- 版本号按 target 单调递增，由应用在插入时取 max+1：
    -- 用全局自增 id 当版本号会让同一个目标的版本号跳跃，运营看不出"这是第几次发布"
    `version`       INT NOT NULL COMMENT '该目标下的版本序号，从 1 开始',
    `content`       MEDIUMTEXT NOT NULL COMMENT '下发内容的完整快照（JSON），回滚即取此内容重发',
    `content_hash`  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '内容摘要，用于跳过"内容没变却重复发布"',
    -- 灰度范围：FULL 全量；GRAY 仅 gray_tenants 列出的租户。
    -- 灰度不是"发一半流量"，而是"先发给指定租户"——SaaS 天然以租户为灰度单元，
    -- 按流量比例灰度会让同一租户的用户看到不一致的行为，反而更难排查
    `publish_scope` VARCHAR(16) NOT NULL DEFAULT 'FULL' COMMENT '发布范围：FULL 全量 / GRAY 灰度',
    `gray_tenants`  VARCHAR(1024) COMMENT '灰度租户编码列表（JSON 数组），publish_scope=GRAY 时有效',
    `data_id`       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '实际发布到的 Nacos dataId',
    `status`        VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '状态：PUBLISHED 已发布 / SUPERSEDED 已被后续版本取代 / FAILED 发布失败',
    `source_version` INT COMMENT '回滚来源版本号；非回滚产生的版本为空',
    `remark`        VARCHAR(500) COMMENT '发布说明',
    `create_by`     BIGINT COMMENT '发布人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    UNIQUE KEY `uk_config_version` (`config_type`, `target_code`, `version`),
    KEY `idx_config_version_target` (`config_type`, `target_code`, `create_time`),
    KEY `idx_config_version_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置发布版本快照（支持对比与回滚）';

-- 本表是平台级的：配置发布由运营方执行，租户只是灰度的目标而非本表的归属者，
-- 故不带 tenant_id，进拦截器忽略清单。

-- ============================================================================
-- 配置版本菜单与权限点（id 从 228 起，227 是 V50 配额计费占用的最后一个）
-- ============================================================================

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (228, 1, '配置版本', 'config-version:view', 1, '/system/config-version', 'DocumentCopy', 'library', 11);

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (229, 228, '回滚配置', 'config-version:rollback', 2, 1),
    (230, 228, '灰度发布', 'config-version:gray', 2, 2);
