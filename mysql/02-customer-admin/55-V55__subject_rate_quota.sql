-- 主体级速率配额（用户额度）的菜单与权限点。
--
-- 本次没有建表：三处数据（cw_subject_quota_level / cw_subject_quota_hit / cw_user.level_code）都在
-- 客服端库 agent_scope_customer_work，由 starter 的 Flyway V4 建，后台只是这份数据的维护端。
-- 刻意不在 admin 库另存副本——限流配置双写出现不一致，表现是"后台显示已放宽、线上照样拦"。
--
-- 挂在"内容风控"下而不是"配额与计费"下：它与同级的"限流规则"是同一件事的两个维度
-- （那边按路径限、这边按人限），而计费那边管的是"这个客户这个月能花多少钱"，周期和目的都不同。

-- 二级菜单：用户额度（sort=4，接在命中看板之后）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (244, 204, '用户额度', 'subject-quota:view', 1, '/contentguard/subject-quota', 'Stopwatch', 'library', 4);

-- 三级：按钮/接口权限点（type=2）。
-- 等级维护与用户分档分开发点：前者改的是"这一档多少额度"（影响一批人），
-- 后者改的是"这个人属于哪一档"（影响一个人），两种误操作的爆炸半径差一个数量级。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (245, 244, '维护等级',   'subject-quota:level-edit', 2, 1),
    (246, 244, '分配用户等级', 'subject-quota:user-edit',  2, 2);
