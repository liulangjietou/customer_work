-- 将历史平台租户归并到唯一保留租户 default。
--
-- 这里只处理 tenant_id = '__platform__' 的存量行，真实业务租户不受影响。
-- 所有 UPDATE 都保留数据库唯一键检查：若 default 下已有同业务键数据，迁移直接失败，
-- 由运维人员确认数据取舍；禁止 UPDATE IGNORE、REPLACE 或静默删除冲突行。

-- MySQL 的多条 DML 不具备整份迁移级原子性，必须在任何业务写入前完成冲突预检。
-- 临时表先放入固定值 1；任一查询命中时再次插入 1，由唯一约束立即中止迁移。
CREATE TEMPORARY TABLE `_v63_platform_tenant_conflict_guard` (
    `singleton` TINYINT NOT NULL,
    UNIQUE KEY `uk_v63_platform_tenant_conflict_guard` (`singleton`)
) ENGINE=InnoDB;

INSERT INTO `_v63_platform_tenant_conflict_guard` (`singleton`) VALUES (1);

INSERT INTO `_v63_platform_tenant_conflict_guard` (`singleton`)
SELECT 1
FROM (
    -- AgentScope 状态表的复合主键冲突。
    SELECT 1 AS `conflict`
    FROM `ai_chat_session_state` p
    INNER JOIN `ai_chat_session_state` d
        ON d.`session_id` = CONCAT(
            'default::', SUBSTRING(p.`session_id`, CHAR_LENGTH('__platform__::') + 1))
       AND d.`state_key` = p.`state_key`
       AND d.`item_index` = p.`item_index`
    WHERE LEFT(p.`session_id`, CHAR_LENGTH('__platform__::')) = '__platform__::'

    UNION ALL

    SELECT 1
    FROM `cw_tenant_usage_daily` p
    INNER JOIN `cw_tenant_usage_daily` d
        ON d.`tenant_id` = 'default'
       AND d.`stat_date` = p.`stat_date`
       AND d.`provider` = p.`provider`
       AND d.`model_name` = p.`model_name`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `ai_workspace_session` p
    INNER JOIN `ai_workspace_session` d
        ON d.`tenant_id` = 'default'
       AND d.`agent_code` = p.`agent_code`
       AND d.`session_id` = p.`session_id`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `ai_runtime_publish_task` p
    INNER JOIN `ai_runtime_publish_task` d
        ON d.`tenant_id` = 'default'
       AND d.`revision` = p.`revision`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `ai_runtime_config_ack` p
    INNER JOIN `ai_runtime_config_ack` d
        ON d.`tenant_id` = 'default'
       AND d.`revision` = p.`revision`
       AND d.`instance_id` = p.`instance_id`
    WHERE p.`tenant_id` = '__platform__'
) conflicts
LIMIT 1;

DROP TEMPORARY TABLE `_v63_platform_tenant_conflict_guard`;

-- AgentScope 状态表没有 tenant_id，租户作用域编码在 session_id 与 JSON user_id 中。
-- 单条 UPDATE 保证复合主键冲突由数据库直接报告，同时兼容不含 user_id 或非 JSON 的历史状态。
UPDATE `ai_chat_session_state`
SET `session_id` = CASE
        WHEN LEFT(`session_id`, CHAR_LENGTH('__platform__::')) = '__platform__::'
            THEN CONCAT('default::', SUBSTRING(`session_id`, CHAR_LENGTH('__platform__::') + 1))
        ELSE `session_id`
    END,
    `state_data` = CASE
        WHEN LEFT(JSON_UNQUOTE(JSON_EXTRACT(
            CASE WHEN JSON_VALID(`state_data`) THEN `state_data` ELSE '{}' END,
            '$.user_id')), CHAR_LENGTH('__platform__::')) = '__platform__::'
            THEN JSON_SET(
                `state_data`,
                '$.user_id',
                CONCAT(
                    'default::',
                    SUBSTRING(
                        JSON_UNQUOTE(JSON_EXTRACT(`state_data`, '$.user_id')),
                        CHAR_LENGTH('__platform__::') + 1)))
        ELSE `state_data`
    END
WHERE LEFT(`session_id`, CHAR_LENGTH('__platform__::')) = '__platform__::'
   OR LEFT(JSON_UNQUOTE(JSON_EXTRACT(
       CASE WHEN JSON_VALID(`state_data`) THEN `state_data` ELSE '{}' END,
       '$.user_id')), CHAR_LENGTH('__platform__::')) = '__platform__::';

-- V49 引入 tenant_id 的 34 张表。
UPDATE `ai_agent` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_backup_model` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_knowledge_base` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_mcp` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_memory` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_skill` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_sub_agent` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_system_tool` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_agent_task` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_channel_binding` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_channel_robot` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_channel_session` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_chat_attachment` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_code_knowledge_chunk` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_code_knowledge_index` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_code_review_task` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_coding_audit_log` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_knowledge_base` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_mcp` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_model_config` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_project` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_project_session` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_scheduled_task` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_scheduled_task_run` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_site_message` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_skill` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_skill_file` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_agent_call_log`
SET `tenant_id` = 'default',
    `user_id` = CASE
        WHEN LEFT(`user_id`, CHAR_LENGTH('__platform__::')) = '__platform__::'
            THEN CONCAT('default::', SUBSTRING(`user_id`, CHAR_LENGTH('__platform__::') + 1))
        ELSE `user_id`
    END
WHERE `tenant_id` = '__platform__';
UPDATE `cw_agent_call_segment` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sys_operation_log` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sys_role` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sys_role_permission` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sys_user` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sys_user_role` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';

-- V59 补齐 tenant_id 的 7 张表。
UPDATE `sql_datasource` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sql_define` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sql_define_param` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `sql_field_transform` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `workbench_site` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `workbench_token` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_config_version`
SET `tenant_id` = CASE
        WHEN `tenant_id` = '__platform__' THEN 'default'
        ELSE `tenant_id`
    END,
    `data_id` = CASE
        WHEN RIGHT(`data_id`, CHAR_LENGTH('-tenant-__platform__')) = '-tenant-__platform__'
            THEN CONCAT(
                LEFT(`data_id`, CHAR_LENGTH(`data_id`) - CHAR_LENGTH('-tenant-__platform__')),
                '-tenant-default')
        ELSE `data_id`
    END,
    `gray_tenants` = CASE
        WHEN JSON_VALID(`gray_tenants`)
         AND JSON_CONTAINS(`gray_tenants`, JSON_QUOTE('__platform__'), '$')
            THEN REPLACE(`gray_tenants`, '"__platform__"', '"default"')
        ELSE `gray_tenants`
    END
WHERE `tenant_id` = '__platform__'
   OR RIGHT(`data_id`, CHAR_LENGTH('-tenant-__platform__')) = '-tenant-__platform__'
   OR (JSON_VALID(`gray_tenants`)
       AND JSON_CONTAINS(`gray_tenants`, JSON_QUOTE('__platform__'), '$'));

-- V59 只补了 tenant_id，旧唯一键仍会让不同租户的同名目标/版本互相冲突。
-- 旧键已保证当前数据在更宽范围内唯一，改成租户内唯一无需额外去重。
ALTER TABLE `ai_config_version`
    DROP INDEX `uk_config_version`,
    ADD UNIQUE KEY `uk_config_version_tenant` (`tenant_id`, `config_type`, `target_code`, `version`);

-- V49 之后新增且自带 tenant_id 的 4 张表。
UPDATE `cw_tenant_usage_daily` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_workspace_session` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `ai_runtime_publish_task`
SET `tenant_id` = CASE
        WHEN `tenant_id` = '__platform__' THEN 'default'
        ELSE `tenant_id`
    END,
    `data_id` = CASE
        WHEN RIGHT(`data_id`, CHAR_LENGTH('-tenant-__platform__')) = '-tenant-__platform__'
            THEN CONCAT(
                LEFT(`data_id`, CHAR_LENGTH(`data_id`) - CHAR_LENGTH('-tenant-__platform__')),
                '-tenant-default')
        ELSE `data_id`
    END,
    `gray_tenants` = CASE
        WHEN JSON_VALID(`gray_tenants`)
         AND JSON_CONTAINS(`gray_tenants`, JSON_QUOTE('__platform__'), '$')
            THEN REPLACE(`gray_tenants`, '"__platform__"', '"default"')
        ELSE `gray_tenants`
    END
WHERE `tenant_id` = '__platform__'
   OR RIGHT(`data_id`, CHAR_LENGTH('-tenant-__platform__')) = '-tenant-__platform__'
   OR (JSON_VALID(`gray_tenants`)
       AND JSON_CONTAINS(`gray_tenants`, JSON_QUOTE('__platform__'), '$'));
UPDATE `ai_runtime_config_ack` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';

-- 控制面能力由角色显式表达，不再借 tenant_id 的特殊字面量推断。
-- 放在数据归一之后，确保唯一键冲突先失败，不留下已加列但迁移未登记的半完成状态。
ALTER TABLE `sys_role`
    ADD COLUMN `control_plane` TINYINT NOT NULL DEFAULT 0 COMMENT '是否控制面角色：0否 / 1是';

UPDATE `sys_role`
SET `control_plane` = 1
WHERE `tenant_id` = 'default'
  AND `role_code` IN ('super_admin', 'operator');

-- 普通租户角色不应持有控制面专属权限。安全边界仍由 Controller 的 control_plane 校验负责；
-- 此处同步清理授权，避免前端按权限点展示一个必然返回 403 的入口。
DELETE `rp`
FROM `sys_role_permission` `rp`
JOIN `sys_role` `r`
  ON `r`.`id` = `rp`.`role_id`
 AND `r`.`tenant_id` = `rp`.`tenant_id`
JOIN `sys_permission` `p` ON `p`.`id` = `rp`.`permission_id`
WHERE `r`.`control_plane` = 0
  AND `p`.`perm_code` IN (
      'tenant:view', 'tenant:add', 'tenant:edit', 'tenant:delete',
      'menu', 'menu:view', 'menu:add', 'menu:edit', 'menu:delete',
      'login-image:view', 'login-image:add', 'login-image:edit', 'login-image:delete',
      'system-tool', 'system-tool:view', 'system-tool:edit',
      'config-version:view', 'config-version:rollback', 'config-version:gray',
      'billing:quota-edit', 'billing:price-edit', 'billing:export',
      'sensitive-word:add', 'sensitive-word:edit', 'sensitive-word:delete'
  );

-- default 已由 V49 建立；全部引用归一后才能删除历史平台租户主数据。
DELETE FROM `sys_tenant` WHERE `tenant_code` = '__platform__';
