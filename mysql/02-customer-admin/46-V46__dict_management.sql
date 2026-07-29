-- 字典管理菜单权限（Flyway V46）。
--
-- 字典数据两表（cw_dict_type / cw_dict_item）在客服端库（agent_scope_customer_work），由 starter 的
-- SchemaInitializer 建表并种子，本迁移只登记 admin 侧菜单与按钮权限点——照内容风控（V42）的先例：
-- admin 直连客服端库维护（DictGatewayProvider 惰性数据源），单一数据真源、不做双写同步。
-- 无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接返回全部权限点。

-- 二级菜单：字典管理（挂系统管理 id=1 下，排在轮播图管理 sort=7 之后）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (216, 1, '字典管理', 'dict:view', 1, '/system/dict', 'Collection', 'library', 8);

-- 三级：按钮/接口权限点（type=2）。类型与字典项共用同一组权限点——
-- 它们是同一能力（维护一份字典）的两级视图，不是两个独立的能力边界。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (217, 216, '新增字典', 'dict:add', 2, 1),
    (218, 216, '编辑字典', 'dict:edit', 2, 2),
    (219, 216, '删除字典', 'dict:delete', 2, 3);
