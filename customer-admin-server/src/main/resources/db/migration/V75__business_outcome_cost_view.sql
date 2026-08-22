-- 业务结果—成本语义看板：只增加菜单权限，事实均从客服端现有表实时只读聚合。
SET NAMES utf8mb4;

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`)
SELECT p.id, '业务结果与成本', 'business-outcome:view', 1,
       '/ops/business-outcome', 'PieChart', 'library', 8
FROM `sys_permission` p
WHERE p.perm_code = 'ops'
  AND NOT EXISTS (
      SELECT 1 FROM `sys_permission` existing
       WHERE existing.perm_code = 'business-outcome:view'
  );
