-- =============================================================================
-- 智能体客服后台管理系统 · 建库建表脚本（Flyway V1，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 说明：
--   1. 生产环境 flyway.enabled=false，本脚本内容与 mysql/admin-schema.sql 保持一致，
--      由 DBA 走变更流程手工执行（与 customer-work 现有"生产不自动建表"约定一致）。
--   2. 采用逻辑删除 + 审计字段规范：id/create_by/create_time/update_by/update_time/deleted。
--   3. 与 customer-work 主业务库物理隔离，使用独立数据库（本地默认 customer_admin）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 系统管理域：用户 / 角色 / 权限（RBAC）
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username`         VARCHAR(64) NOT NULL COMMENT '登录账号',
    `password`         VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密存储）',
    `nickname`         VARCHAR(64) COMMENT '昵称',
    `status`           TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 / 1启用',
    `last_login_time`  DATETIME COMMENT '最后登录时间',
    `last_login_ip`    VARCHAR(64) COMMENT '最后登录 IP',
    `create_by`        BIGINT COMMENT '创建人ID',
    `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`        BIGINT COMMENT '更新人ID',
    `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_sys_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台用户';

CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `role_name`    VARCHAR(64) NOT NULL COMMENT '角色名称',
    `role_code`    VARCHAR(64) NOT NULL COMMENT '角色编码（唯一，如 super_admin）',
    `remark`       VARCHAR(255) COMMENT '备注',
    `status`       TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 / 1启用',
    `create_by`    BIGINT COMMENT '创建人ID',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    BIGINT COMMENT '更新人ID',
    `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_sys_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `parent_id`    BIGINT NOT NULL DEFAULT 0 COMMENT '父权限ID（支持菜单树，0=根节点）',
    `perm_name`    VARCHAR(64) NOT NULL COMMENT '权限/菜单名称',
    `perm_code`    VARCHAR(128) NOT NULL COMMENT '权限标识（如 mcp:add / skill:delete）',
    `type`         TINYINT NOT NULL COMMENT '类型：1菜单 / 2按钮 / 3接口',
    `path`         VARCHAR(255) COMMENT '前端路由 / 接口路径',
    `icon`         VARCHAR(64) COMMENT '菜单图标',
    `sort`         INT NOT NULL DEFAULT 0 COMMENT '排序',
    `create_by`    BIGINT COMMENT '创建人ID',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    BIGINT COMMENT '更新人ID',
    `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_sys_permission_code` (`perm_code`),
    INDEX `idx_sys_permission_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限/菜单（树形）';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`       BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`  BIGINT NOT NULL COMMENT '用户ID',
    `role_id`  BIGINT NOT NULL COMMENT '角色ID',
    UNIQUE KEY `uk_sys_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-角色关联';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `role_id`        BIGINT NOT NULL COMMENT '角色ID',
    `permission_id`  BIGINT NOT NULL COMMENT '权限ID',
    UNIQUE KEY `uk_sys_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-权限关联';

CREATE TABLE IF NOT EXISTS `sys_operation_log` (
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`      BIGINT COMMENT '操作人ID（登录失败等未认证场景可为空）',
    `username`     VARCHAR(64) COMMENT '操作人账号',
    `operation`    VARCHAR(128) NOT NULL COMMENT '操作类型（登录/登出/新增/修改/删除/测试等）',
    `method`       VARCHAR(255) COMMENT '请求方法/接口',
    `target`       VARCHAR(128) COMMENT '操作对象（模块+资源标识）',
    `params`       TEXT COMMENT '请求参数（脱敏后）',
    `result`       TINYINT NOT NULL COMMENT '结果：1成功 / 0失败',
    `error_msg`    VARCHAR(512) COMMENT '失败原因',
    `ip`           VARCHAR(64) COMMENT '操作IP',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX `idx_sys_operation_log_user` (`user_id`),
    INDEX `idx_sys_operation_log_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志（含登录/登出日志）';

-- -----------------------------------------------------------------------------
-- AI 配置域：模型 / MCP / Skill / 智能体
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `ai_model_config` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `model_name`    VARCHAR(64) NOT NULL COMMENT '模型名称（自定义标识）',
    `provider`      VARCHAR(32) NOT NULL DEFAULT 'openai' COMMENT '提供方（当前固定 openai，预留扩展）',
    `api_key`       VARCHAR(512) NOT NULL COMMENT 'AppKey（AES/GCM 加密存储）',
    `base_url`      VARCHAR(255) NOT NULL COMMENT '接口 URL',
    `model`         VARCHAR(64) NOT NULL COMMENT '模型名（如 gpt-4o）',
    `temperature`   DECIMAL(3,2) COMMENT '采样温度',
    `max_tokens`    INT COMMENT '最大 token 数',
    `is_default`    TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认模型：0否 / 1是',
    `status`        TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 / 1启用',
    `test_status`   TINYINT NOT NULL DEFAULT 0 COMMENT '最近测试结果：0未测试 / 1成功 / 2失败',
    `test_time`     DATETIME COMMENT '最近测试时间',
    `create_by`     BIGINT COMMENT '创建人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT COMMENT '更新人ID',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型配置';

CREATE TABLE IF NOT EXISTS `ai_mcp` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `mcp_name`      VARCHAR(64) NOT NULL COMMENT 'MCP 名称',
    `mcp_type`      VARCHAR(32) NOT NULL COMMENT '类型（stdio / sse）',
    `config`        TEXT NOT NULL COMMENT 'MCP 连接配置（命令/URL/参数等，JSON）',
    `description`   VARCHAR(255) COMMENT '描述',
    `status`        TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 / 1启用',
    `create_by`     BIGINT COMMENT '创建人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT COMMENT '更新人ID',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP 配置';

CREATE TABLE IF NOT EXISTS `ai_skill` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `skill_name`    VARCHAR(64) NOT NULL COMMENT '技能名称',
    `skill_code`    VARCHAR(64) NOT NULL COMMENT '技能编码',
    `content`       TEXT NOT NULL COMMENT '技能内容/定义（SKILL.md 内容）',
    `description`   VARCHAR(255) COMMENT '描述',
    `status`        TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 / 1启用',
    `create_by`     BIGINT COMMENT '创建人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT COMMENT '更新人ID',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_ai_skill_code` (`skill_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 配置';

CREATE TABLE IF NOT EXISTS `ai_agent` (
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `agent_name`       VARCHAR(64) NOT NULL COMMENT '智能体名称',
    `agent_code`       VARCHAR(64) NOT NULL COMMENT '智能体编码（用于动态菜单路由，[a-z0-9-]+）',
    `model_id`         BIGINT NOT NULL COMMENT '关联模型ID（必填）',
    `system_prompt`    TEXT COMMENT '系统提示词',
    `capabilities`     VARCHAR(128) NOT NULL DEFAULT 'chat' COMMENT '能力标识（逗号分隔：chat,vibecoding）',
    `icon`             VARCHAR(64) COMMENT '菜单图标',
    `status`           TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用 / 1启用',
    `create_by`        BIGINT COMMENT '创建人ID',
    `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`        BIGINT COMMENT '更新人ID',
    `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除（会话历史归档保留）',
    UNIQUE KEY `uk_ai_agent_code` (`agent_code`),
    INDEX `idx_ai_agent_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体配置';

CREATE TABLE IF NOT EXISTS `ai_agent_mcp` (
    `id`        BIGINT AUTO_INCREMENT PRIMARY KEY,
    `agent_id`  BIGINT NOT NULL COMMENT '智能体ID',
    `mcp_id`    BIGINT NOT NULL COMMENT 'MCP ID',
    UNIQUE KEY `uk_ai_agent_mcp` (`agent_id`, `mcp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-MCP关联';

CREATE TABLE IF NOT EXISTS `ai_agent_skill` (
    `id`        BIGINT AUTO_INCREMENT PRIMARY KEY,
    `agent_id`  BIGINT NOT NULL COMMENT '智能体ID',
    `skill_id`  BIGINT NOT NULL COMMENT 'Skill ID',
    UNIQUE KEY `uk_ai_agent_skill` (`agent_id`, `skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-Skill关联';
