-- 审核绑定租户前先修正角色领域约束：角色编码只要求租户内唯一，并补齐存量租户的内建管理员角色。
SET NAMES utf8mb4;

SET @v99_role_table_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'sys_role');
SET @v99_role_columns = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_role'
      AND column_name IN ('tenant_id', 'role_code', 'deleted', 'data_scope', 'control_plane'));
SET @v99_preflight_sql = IF(@v99_role_table_exists = 1 AND @v99_role_columns = 5,
    'SELECT 1', 'SELECT * FROM `__customer_admin_v99_sys_role_preflight_failed__`');
PREPARE v99_preflight_stmt FROM @v99_preflight_sql;
EXECUTE v99_preflight_stmt;
DEALLOCATE PREPARE v99_preflight_stmt;

-- 若历史环境曾手工移除全局唯一键，先阻止带租户内重复编码的数据进入正式约束。
SET @v99_duplicate_role_codes = (SELECT COUNT(*) FROM (
    SELECT `tenant_id`, `role_code`
    FROM `sys_role`
    GROUP BY `tenant_id`, `role_code`
    HAVING COUNT(*) > 1
) AS duplicate_role_codes);
SET @v99_duplicate_guard_sql = IF(@v99_duplicate_role_codes = 0,
    'SELECT 1', 'SELECT * FROM `__customer_admin_v99_duplicate_tenant_role_code__`');
PREPARE v99_duplicate_guard_stmt FROM @v99_duplicate_guard_sql;
EXECUTE v99_duplicate_guard_stmt;
DEALLOCATE PREPARE v99_duplicate_guard_stmt;

-- 兼容已经人工按本迁移目标改过索引的环境；同名但结构错误时 fail-fast，避免静默留下伪约束。
SET @v99_old_index_shape = (SELECT CONCAT(MIN(`non_unique`), ':',
        GROUP_CONCAT(`column_name` ORDER BY `seq_in_index` SEPARATOR ','))
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sys_role'
      AND index_name = 'uk_sys_role_code');
SET @v99_target_index_shape = (SELECT CONCAT(MIN(`non_unique`), ':',
        GROUP_CONCAT(`column_name` ORDER BY `seq_in_index` SEPARATOR ','))
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sys_role'
      AND index_name = 'uk_sys_role_tenant_code');
SET @v99_index_guard_sql = IF(
    (@v99_old_index_shape IS NULL OR @v99_old_index_shape IN ('0:role_code', '0:tenant_id,role_code'))
        AND (@v99_target_index_shape IS NULL OR @v99_target_index_shape = '0:tenant_id,role_code'),
    'SELECT 1', 'SELECT * FROM `__customer_admin_v99_role_index_preflight_failed__`');
PREPARE v99_index_guard_stmt FROM @v99_index_guard_sql;
EXECUTE v99_index_guard_stmt;
DEALLOCATE PREPARE v99_index_guard_stmt;

SET @v99_drop_global_index_sql = IF(@v99_old_index_shape IS NULL, 'SELECT 1',
    'ALTER TABLE `sys_role` DROP INDEX `uk_sys_role_code`');
PREPARE v99_drop_global_index_stmt FROM @v99_drop_global_index_sql;
EXECUTE v99_drop_global_index_stmt;
DEALLOCATE PREPARE v99_drop_global_index_stmt;

SET @v99_add_tenant_index_sql = IF(@v99_target_index_shape IS NULL,
    'ALTER TABLE `sys_role` ADD UNIQUE KEY `uk_sys_role_tenant_code` (`tenant_id`, `role_code`)',
    'SELECT 1');
PREPARE v99_add_tenant_index_stmt FROM @v99_add_tenant_index_sql;
EXECUTE v99_add_tenant_index_stmt;
DEALLOCATE PREPARE v99_add_tenant_index_stmt;

-- V49 之后创建租户时会初始化 tenant_admin；旧唯一键使第二个租户必然撞键。
-- 迁移完成后为全部可用存量租户补齐内建角色，审核页才能真正选择目标租户的角色。
INSERT INTO `sys_role`
    (`role_name`, `role_code`, `remark`, `status`, `tenant_id`, `data_scope`, `control_plane`)
SELECT '租户管理员', 'tenant_admin', '租户开通时自动创建，拥有本租户内全部管理权限',
       1, t.`tenant_code`, 'TENANT', 0
FROM `sys_tenant` t
WHERE t.`deleted` = 0
  AND t.`status` = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role` r
      WHERE r.`tenant_id` = t.`tenant_code`
        AND r.`role_code` = 'tenant_admin'
  );

-- 与 ControlPlanePermissions 保持同一安全边界：tenant_admin 获得租户内管理权限，
-- 但不能获得租户、菜单、登录图、系统工具、配置发布、计费写入和敏感词写入等控制面权限。
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `tenant_id`)
SELECT r.`id`, p.`id`, r.`tenant_id`
FROM `sys_role` r
CROSS JOIN `sys_permission` p
LEFT JOIN `sys_role_permission` rp
  ON rp.`role_id` = r.`id`
 AND rp.`permission_id` = p.`id`
WHERE r.`role_code` = 'tenant_admin'
  AND r.`deleted` = 0
  AND rp.`id` IS NULL
  AND p.`perm_code` NOT IN (
      'tenant', 'menu', 'login-image', 'system-tool', 'config-version',
      'billing:quota-edit', 'billing:price-edit', 'billing:export', 'billing:aggregate',
      'sensitive-word:add', 'sensitive-word:edit', 'sensitive-word:delete'
  )
  AND p.`perm_code` NOT LIKE 'tenant:%'
  AND p.`perm_code` NOT LIKE 'menu:%'
  AND p.`perm_code` NOT LIKE 'login-image:%'
  AND p.`perm_code` NOT LIKE 'system-tool:%'
  AND p.`perm_code` NOT LIKE 'config-version:%';
