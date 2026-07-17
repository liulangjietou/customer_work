-- =============================================================================
-- 用户工单菜单权限（Flyway V19，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 后台"客服工单/用户工单"模块：工单数据全部在 customer-work-app-server（8080）侧，admin-server 只作
-- HTTP 代理坐席 API + 给前端签发坐席 WS 接入凭证，本模块不建业务表，仅登记菜单/按钮权限点。
--
-- 权限点 id 段：现有 sys_permission 显式 id 最大到 126（V15 系统工具的 124~126），auto-increment
-- 按钮亦未超过。本次一律用显式 id 130~138（跳过 127~129 留缓冲，且远离既有值），
-- 避免与历史迁移/自增按钮相互冲突，取值确定可复现。
--
-- '客服工单' 是新的一级分组（parent_id=0，与 system/aiconfig/workspace/project/sql-config 平级，
-- sort=6 排在最后）；'用户工单' 二级菜单挂其下；按钮沿用 view + 业务动作
-- （claim/reply/transfer/resolve/close）+ edit（挂起/恢复/优先级/分类等编辑类操作共用一个权限点）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接放行。
-- =============================================================================

-- 一级分组：客服工单（type=1，parent_id=0）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (130, 0, '客服工单', 'ticket', 1, '/ticket', 'Headset', 'library', 6);

-- 二级菜单：用户工单（type=1，挂 130 下）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (131, 130, '用户工单', 'user-ticket', 1, '/ticket/user-ticket', 'Tickets', 'library', 1);

-- 三级：按钮/接口权限点（type=2，挂 131 下）。claim/reply/transfer/resolve/close 各为有副作用的独立
-- 授权点；edit 覆盖挂起/恢复/优先级/分类等编辑类操作。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (132, 131, '查看用户工单',   'user-ticket:view',     2, 1),
    (133, 131, '抢单受理工单',   'user-ticket:claim',    2, 2),
    (134, 131, '回复工单',       'user-ticket:reply',    2, 3),
    (135, 131, '转派工单',       'user-ticket:transfer', 2, 4),
    (136, 131, '解决工单',       'user-ticket:resolve',  2, 5),
    (137, 131, '关闭工单',       'user-ticket:close',    2, 6),
    (138, 131, '编辑工单',       'user-ticket:edit',     2, 7);
