-- 内容风控（敏感词词库 / 限流规则 / 命中看板）的菜单与权限点。
--
-- 本次没有建表：三张数据表（cw_sensitive_word / cw_rate_limit_rule / cw_sensitive_word_hit_log）都在
-- 客服端库 agent_scope_customer_work，由 starter 的 SchemaInitializer 建，后台只是这份数据的维护端。
-- 刻意不在 admin 库另存副本——风控配置双写出现不一致是要出事故的。
--
-- 独立成一级菜单而非挂"AI 配置"下：内容风控管的是"发出去/收进来的话能不能过"，
-- 与智能体/模型/MCP 那类能力配置不是一回事；且三个页面挂进去会让 AI 配置菜单长到十一项。

-- 一级菜单：内容风控（sort=8，排在客服工单之后）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (204, 0, '内容风控', 'contentguard', 1, '/contentguard', 'Lock', 'library', 8);

-- 二级菜单：三个页面。菜单可见性即各自的 view 权限点。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (205, 204, '敏感词词库', 'sensitive-word:view',   1, '/contentguard/sensitive-word', 'ChatLineSquare', 'library', 1),
    (209, 204, '限流规则',   'rate-limit-rule:view',  1, '/contentguard/rate-limit',     'Odometer',       'library', 2),
    (213, 204, '命中看板',   'sensitive-hit-log:view', 1, '/contentguard/hit-log',       'DataAnalysis',   'library', 3);

-- 三级：按钮/接口权限点（type=2）。
-- 导入复用 add、导出复用 view，不单独发权限点——它们不是独立的能力边界，只是同一能力的不同入口。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (206, 205, '新增敏感词', 'sensitive-word:add',    2, 1),
    (207, 205, '编辑敏感词', 'sensitive-word:edit',   2, 2),
    (208, 205, '删除敏感词', 'sensitive-word:delete', 2, 3),
    (210, 209, '新增限流规则', 'rate-limit-rule:add',    2, 1),
    (211, 209, '编辑限流规则', 'rate-limit-rule:edit',   2, 2),
    (212, 209, '删除限流规则', 'rate-limit-rule:delete', 2, 3);
