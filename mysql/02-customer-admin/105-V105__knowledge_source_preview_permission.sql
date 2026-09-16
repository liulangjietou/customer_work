-- 原文预览是独立的数据出口，不给已有的知识库查看/编辑角色隐式追加权限。
-- 全局超级管理员仍沿用现有权限解析；业务角色由管理员在角色权限中明确授予。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT `p`.`id`, '预览知识原文', 'knowledge-base:source-preview', 2, 5
FROM `sys_permission` `p`
WHERE `p`.`perm_code` = 'knowledge-base:view'
  AND NOT EXISTS (
      SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'knowledge-base:source-preview'
  );
