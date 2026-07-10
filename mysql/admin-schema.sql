-- =============================================================================
-- 智能体客服后台管理系统 · 建库建表脚本（DBA 预审 / 受限权限环境用）
-- =============================================================================
-- 说明：
--   1. 与 mysql/schema.sql（客服主业务库）物理隔离，使用独立数据库，避免误操作影响生产客服数据。
--   2. 内容与 customer-admin-server/src/main/resources/db/migration/V1__init_schema.sql、
--      V2__seed_data.sql 保持一致——应用侧仅在本地/测试 profile 用 Flyway 自动执行，
--      生产环境 flyway.enabled=false，由 DBA 参照本脚本手工执行。
--   3. 采用逻辑删除 + 审计字段规范：id/create_by/create_time/update_by/update_time/deleted。
--
-- 执行：mysql -h localhost -u root -proot < mysql/admin-schema.sql
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `customer_admin`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `customer_admin`;

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
    `password`         VARCHAR(128) NULL COMMENT '密码（BCrypt 加密存储；LDAP 域账号为 NULL，见 login_type）',
    `nickname`         VARCHAR(64) COMMENT '昵称',
    `login_type`       VARCHAR(16) NOT NULL DEFAULT 'LOCAL' COMMENT '账号来源：LOCAL本地账号 / LDAP域账号(OA单点登录)',
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
    `icon`         VARCHAR(255) COMMENT '图标库图标名或上传图片URL（按 icon_type 区分）',
    `icon_type`    VARCHAR(16) NOT NULL DEFAULT 'library' COMMENT '图标类型：library=图标库图标名，image=上传图片URL',
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

CREATE TABLE IF NOT EXISTS `sys_menu_change_log` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `menu_id`         BIGINT NOT NULL COMMENT '被操作的菜单节点ID（删除后节点已不存在，此处仍保留历史指向）',
    `action`          VARCHAR(16) NOT NULL COMMENT '操作类型：CREATE/UPDATE/DELETE/MOVE',
    `before_snapshot` TEXT COMMENT '变更前节点 JSON（CREATE 时为空）',
    `after_snapshot`  TEXT COMMENT '变更后节点 JSON（DELETE 时为空）',
    `operator_id`     BIGINT COMMENT '操作人用户ID',
    `operator_name`   VARCHAR(64) COMMENT '操作人昵称（冗余存储，用户改名/删除后历史记录仍可读）',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX `idx_menu_change_log_menu` (`menu_id`),
    INDEX `idx_menu_change_log_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单变更审计流水（排查用，不支持回滚）';

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
    `mcp_type`      VARCHAR(32) NOT NULL COMMENT '类型（stdio / sse / http）',
    `config`        TEXT NOT NULL COMMENT 'MCP 连接配置（命令/URL/参数等，JSON）',
    `description`   VARCHAR(255) COMMENT '描述',
    `status`        TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 / 1启用',
    `test_status`   TINYINT NOT NULL DEFAULT 0 COMMENT '连通性测试：0未测试 / 1成功 / 2失败',
    `test_time`     DATETIME NULL COMMENT '最近一次测试时间',
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

-- 对话历史持久化：AgentScope 框架自带的 MysqlAgentStateStore 表结构（表结构定义见
-- io.agentscope.extensions.mysql.state.MysqlAgentStateStore 类注释），自定义表名而非框架默认的
-- agentscope_sessions，与本库 ai_/sys_ 前缀命名约定保持一致。
CREATE TABLE IF NOT EXISTS `ai_chat_session_state` (
    `session_id`  VARCHAR(255) NOT NULL,
    `state_key`   VARCHAR(255) NOT NULL,
    `item_index`  INT NOT NULL DEFAULT 0,
    `state_data`  LONGTEXT NOT NULL,
    `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`, `state_key`, `item_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话状态持久化（AgentStateStore，含短期记忆/对话历史）';
-- =============================================================================
-- 智能体客服后台管理系统 · 种子数据（Flyway V2，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 说明：
--   1. 默认超级管理员 admin/admin（密码 BCrypt 加密，已本地校验 matches("admin", hash)=true），
--      首次登录由 AuthService 判断当前密码等于本初始哈希值时强制要求改密，不额外加表字段。
--   2. 超级管理员角色不冗余插入 sys_role_permission 记录——AdminStpInterfaceImpl 对
--      role_code=super_admin 特判直接放行全部权限，见 3.1 节设计。
--   3. 运营管理员角色默认空权限，由超管登录后手动勾选分配。
--   4. 权限树对应需求文档"二、菜单规划"三大分组：系统管理 / AI 配置 / 智能体工作区
--      （工作区节点静态存在，子节点由 MenuAggregationService 运行时动态拼接，不落库）。
-- =============================================================================

-- ---- 默认超级管理员 ----
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `status`)
VALUES (1, 'admin', '$2a$10$M7Z.8TA1.6l01JSeZRGAb.olJkoDmvk4JSX81kNlZ5rzE1LCsDCFC', '超级管理员', 1);

-- ---- 默认角色 ----
INSERT INTO `sys_role` (`id`, `role_name`, `role_code`, `remark`, `status`) VALUES
    (1, '超级管理员', 'super_admin', '拥有全部权限，系统初始化内置', 1),
    (2, '运营管理员', 'operator', '默认无权限，需超管手动分配', 1);

INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1, 1);

-- ---- 默认权限树 ----
-- 一级：系统管理 / AI 配置 / 智能体工作区（type=1 菜单，parent_id=0）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `sort`) VALUES
    (1,  0, '系统管理',     'system',    1, '/system',    1),
    (2,  0, 'AI 配置',      'aiconfig',  1, '/aiconfig',  2),
    (3,  0, '智能体工作区', 'workspace', 1, '/workspace', 3);

-- 二级：系统管理 子菜单（type=1）；icon 为 Element Plus 图标组件名（全局已注册，见 main.ts），
-- 具体颜色由前端 MenuTree.vue 按 perm_code 映射着色，不在这里存颜色值（图标库换皮不用改数据）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `sort`) VALUES
    (10, 1, '用户管理', 'user', 1, '/system/user', 'User',    1),
    (11, 1, '角色权限', 'role', 1, '/system/role', 'Avatar',  2),
    (12, 1, '操作日志', 'log',  1, '/system/log',  'Tickets', 3),
    (13, 1, '菜单管理', 'menu', 1, '/system/menu', 'Menu',    4);

-- 二级：AI 配置 子菜单（type=1）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `sort`) VALUES
    (20, 2, '模型配置',   'model', 1, '/aiconfig/model', 'Cpu',        1),
    (21, 2, 'MCP 管理',   'mcp',   1, '/aiconfig/mcp',   'Connection', 2),
    (22, 2, 'Skill 管理', 'skill', 1, '/aiconfig/skill', 'Tools',      3),
    (23, 2, '智能体管理', 'agent', 1, '/aiconfig/agent', 'MagicStick', 4);

-- 三级：按钮/接口权限点（type=2），user/role/model/mcp/skill/agent 各 4 个（view/add/edit/delete），log 只读
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (10, '查看用户', 'user:view',   2, 1), (10, '新增用户', 'user:add',   2, 2),
    (10, '编辑用户', 'user:edit',   2, 3), (10, '删除用户', 'user:delete', 2, 4),
    (11, '查看角色', 'role:view',   2, 1), (11, '新增角色', 'role:add',   2, 2),
    (11, '编辑角色', 'role:edit',   2, 3), (11, '删除角色', 'role:delete', 2, 4),
    (12, '查看日志', 'log:view',    2, 1),
    (13, '查看菜单', 'menu:view',   2, 1), (13, '新增菜单', 'menu:add',   2, 2),
    (13, '编辑菜单', 'menu:edit',   2, 3), (13, '删除菜单', 'menu:delete', 2, 4),
    (20, '查看模型', 'model:view',  2, 1), (20, '新增模型', 'model:add',  2, 2),
    (20, '编辑模型', 'model:edit',  2, 3), (20, '删除模型', 'model:delete', 2, 4),
    (21, '查看MCP',  'mcp:view',    2, 1), (21, '新增MCP',  'mcp:add',    2, 2),
    (21, '编辑MCP',  'mcp:edit',    2, 3), (21, '删除MCP',  'mcp:delete', 2, 4),
    (22, '查看Skill', 'skill:view', 2, 1), (22, '新增Skill', 'skill:add', 2, 2),
    (22, '编辑Skill', 'skill:edit', 2, 3), (22, '删除Skill', 'skill:delete', 2, 4),
    (23, '查看智能体', 'agent:view', 2, 1), (23, '新增智能体', 'agent:add', 2, 2),
    (23, '编辑智能体', 'agent:edit', 2, 3), (23, '删除智能体', 'agent:delete', 2, 4);
