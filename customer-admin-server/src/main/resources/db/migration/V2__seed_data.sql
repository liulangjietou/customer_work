-- =============================================================================
-- 客服后台管理系统 · 种子数据（Flyway V2，仅本地/测试 profile 自动执行）
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

-- 二级：系统管理 子菜单（type=1）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `sort`) VALUES
    (10, 1, '用户管理', 'user', 1, '/system/user', 1),
    (11, 1, '角色权限', 'role', 1, '/system/role', 2),
    (12, 1, '操作日志', 'log',  1, '/system/log',  3);

-- 二级：AI 配置 子菜单（type=1）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `sort`) VALUES
    (20, 2, '模型配置',   'model', 1, '/aiconfig/model', 1),
    (21, 2, 'MCP 管理',   'mcp',   1, '/aiconfig/mcp',   2),
    (22, 2, 'Skill 管理', 'skill', 1, '/aiconfig/skill', 3),
    (23, 2, '智能体管理', 'agent', 1, '/aiconfig/agent', 4);

-- 三级：按钮/接口权限点（type=2），user/role/model/mcp/skill/agent 各 4 个（view/add/edit/delete），log 只读
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (10, '查看用户', 'user:view',   2, 1), (10, '新增用户', 'user:add',   2, 2),
    (10, '编辑用户', 'user:edit',   2, 3), (10, '删除用户', 'user:delete', 2, 4),
    (11, '查看角色', 'role:view',   2, 1), (11, '新增角色', 'role:add',   2, 2),
    (11, '编辑角色', 'role:edit',   2, 3), (11, '删除角色', 'role:delete', 2, 4),
    (12, '查看日志', 'log:view',    2, 1),
    (20, '查看模型', 'model:view',  2, 1), (20, '新增模型', 'model:add',  2, 2),
    (20, '编辑模型', 'model:edit',  2, 3), (20, '删除模型', 'model:delete', 2, 4),
    (21, '查看MCP',  'mcp:view',    2, 1), (21, '新增MCP',  'mcp:add',    2, 2),
    (21, '编辑MCP',  'mcp:edit',    2, 3), (21, '删除MCP',  'mcp:delete', 2, 4),
    (22, '查看Skill', 'skill:view', 2, 1), (22, '新增Skill', 'skill:add', 2, 2),
    (22, '编辑Skill', 'skill:edit', 2, 3), (22, '删除Skill', 'skill:delete', 2, 4),
    (23, '查看智能体', 'agent:view', 2, 1), (23, '新增智能体', 'agent:add', 2, 2),
    (23, '编辑智能体', 'agent:edit', 2, 3), (23, '删除智能体', 'agent:delete', 2, 4);
