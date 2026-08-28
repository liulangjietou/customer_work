-- 对外开放注册加固：注册联系方式 + 回收租户角色的越权授权。
--
-- 背景：TenantProvisionService 给新租户管理员授予“除控制面专属外的全部权限点”，
-- 而控制面专属清单此前只挡了 tenant/menu/login-image/system-tool/config-version 五族。
-- 本次把 ControlPlanePermissions 扩到 29 族（含 9 个内部运维工具族），存量租户角色上
-- 已经发出去的授权不会自己消失，必须在这里回收。
--
-- 幂等性：两段都是“不存在才加 / 匹配到才删”，空库执行是无操作，重复执行结果一致。

-- ---------------------------------------------------------------------------
-- 1. 注册联系方式：公网自助注册必须能验证真人、通知审核结果、找回密码
-- ---------------------------------------------------------------------------
-- 唯一键允许多行 NULL，故存量账号（email 全为 NULL）不受影响；
-- 内网 LDAP 账号也可以一直不填。
SET @v101_email_exists = (SELECT COUNT(*) FROM `information_schema`.`COLUMNS`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'sys_user' AND `COLUMN_NAME` = 'email');
SET @v101_email_sql = IF(@v101_email_exists > 0, 'SELECT 1',
    'ALTER TABLE `sys_user`
        ADD COLUMN `email` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''注册邮箱（自助注册必填，LDAP/预建账号可空）'' AFTER `nickname`,
        ADD COLUMN `email_verified` tinyint NOT NULL DEFAULT 0 COMMENT ''邮箱是否已验证：0否 / 1是'' AFTER `email`,
        ADD UNIQUE KEY `uk_sys_user_email` (`email`)');
PREPARE v101_email_stmt FROM @v101_email_sql; EXECUTE v101_email_stmt; DEALLOCATE PREPARE v101_email_stmt;

-- ---------------------------------------------------------------------------
-- 2. 回收存量租户角色上的越权授权
-- ---------------------------------------------------------------------------
-- 只动 control_plane = 0 的角色：控制面角色（超管/平台运维）本就该保有这些权限。
-- 族清单与 ControlPlanePermissions 的 CONTROL_PLANE_FAMILIES + INTERNAL_TOOL_FAMILIES 一一对应，
-- 两处必须同增同减——漏改这里，存量租户会继续留着已经被判定为越权的授权行。
DELETE `rp` FROM `sys_role_permission` `rp`
    JOIN `sys_role` `r` ON `r`.`id` = `rp`.`role_id`
    JOIN `sys_permission` `p` ON `p`.`id` = `rp`.`permission_id`
WHERE `r`.`control_plane` = 0
  AND (
    SUBSTRING_INDEX(`p`.`perm_code`, ':', 1) IN (
        -- 平台形态定义
        'tenant', 'menu', 'login-image', 'system-tool', 'config-version', 'dict',
        -- 操作租户忽略表（ai_model_config 全平台一份）
        'model', 'model-experiment',
        -- 能把代码/流量带出本进程
        'mcp', 'skill',
        -- 视野是全平台而非本租户
        'billing', 'slo', 'ai-audit', 'dead-letter', 'semantic-cache',
        'sensitive-word', 'sensitive-hit-log', 'rate-limit-rule', 'governance', 'improvement',
        -- 内部运维工具：SQL 客户端 / 账号本 / 开发者工具箱
        'workbench', 'workbench-site', 'devtools', 'audit',
        'sql-console', 'sql-config', 'sql-datasource', 'sql-define', 'sql-query'
    )
    -- 等级定义即额度上限本身，租户管理员能改等级等于能自己给自己提额
    OR `p`.`perm_code` = 'subject-quota:level-edit'
  );

-- 收尾自检：回收后不应再有任何非控制面角色持有受限权限点。
-- 上面的 DELETE 若因权限或锁失败会静默留下残行，这里让它直接炸掉迁移，
-- 而不是留一个“看起来跑过了”的半收敛状态。
SET @v101_remaining = (SELECT COUNT(*) FROM `sys_role_permission` `rp`
    JOIN `sys_role` `r` ON `r`.`id` = `rp`.`role_id`
    JOIN `sys_permission` `p` ON `p`.`id` = `rp`.`permission_id`
    WHERE `r`.`control_plane` = 0
      AND (SUBSTRING_INDEX(`p`.`perm_code`, ':', 1) IN (
            'tenant', 'menu', 'login-image', 'system-tool', 'config-version', 'dict',
            'model', 'model-experiment', 'mcp', 'skill',
            'billing', 'slo', 'ai-audit', 'dead-letter', 'semantic-cache',
            'sensitive-word', 'sensitive-hit-log', 'rate-limit-rule', 'governance', 'improvement',
            'workbench', 'workbench-site', 'devtools', 'audit',
            'sql-console', 'sql-config', 'sql-datasource', 'sql-define', 'sql-query')
        OR `p`.`perm_code` = 'subject-quota:level-edit'));
SET @v101_verify_sql = IF(@v101_remaining = 0, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v101_control_plane_grant_not_revoked__`');
PREPARE v101_verify_stmt FROM @v101_verify_sql;
EXECUTE v101_verify_stmt;
DEALLOCATE PREPARE v101_verify_stmt;
