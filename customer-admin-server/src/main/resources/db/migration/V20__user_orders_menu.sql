-- =============================================================================
-- 用户订单菜单权限（Flyway V20，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 后台"客服工单/用户订单"模块：订单数据全部在 customer-work-app-server（8080）侧，admin-server 只作
-- HTTP 代理坐席订单 API（查询/详情/改址/取消），本模块不建业务表，仅登记菜单/按钮权限点。
--
-- 权限点 id 段：V19 用到 130~138，本次续用 139~141（连续无空档，远离自增按钮）。
-- '用户订单' 二级菜单挂在 V19 建的一级分组 '客服工单'（id=130）下，sort=2 排在 '用户工单'（sort=1）之后；
-- 按钮为 view（查看列表/详情）+ edit（改址/取消等有副作用的编辑类操作共用一个权限点）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接放行。
-- =============================================================================

-- 二级菜单：用户订单（type=1，挂 130 下）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (139, 130, '用户订单', 'user-order', 1, '/ticket/user-order', 'List', 'library', 2);

-- 三级：按钮/接口权限点（type=2，挂 139 下）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (140, 139, '查看用户订单', 'user-order:view', 2, 1),
    (141, 139, '编辑用户订单', 'user-order:edit', 2, 2);
