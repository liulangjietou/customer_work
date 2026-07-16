-- =============================================================================
-- 一级菜单（系统管理/AI 配置/智能体工作区）图标回填（Flyway V10，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 一级分组节点一直没有图标（V5/V8 只回填了叶子菜单），侧边栏统一显示成默认的 Folder 图标，
-- 没有区分度。这里补上，Element Plus 图标组件名，颜色由前端 MenuTree.vue 按 perm_code 映射着色。

UPDATE `sys_permission` SET `icon` = 'Setting'     WHERE `perm_code` = 'system';
UPDATE `sys_permission` SET `icon` = 'Opportunity' WHERE `perm_code` = 'aiconfig';
UPDATE `sys_permission` SET `icon` = 'Service'     WHERE `perm_code` = 'workspace';
