-- ============================================================================
-- 补齐 V49 遗漏的 tenant_id：SQL 配置四表、内网工作台两表、配置版本表
--
-- V49 按显式清单给 34 张表加了列，但漏掉了 V14 的 sql_* 四表与 V29/V30 的 workbench_* 两表；
-- V51 新增 ai_config_version 时也没带上（违反"新增业务表一律带 tenant_id"的约定）。
-- 这 7 张表既无列、又不在 TenantInterceptors 的忽略清单里——开启 admin.tenant.enabled 后
-- 拦截器会给它们拼一个不存在的列，相关页面当场 Unknown column 报 500。
--
-- login_carousel_image 刻意不加列，改进忽略清单：登录页是平台统一入口（sys_user.username 全局唯一，
-- 全系统只有一个登录页），/api/login-images/** 在登录前匿名访问，此时没有任何租户上下文可用，
-- 加列参与过滤只会让登录页 fail-closed 打不开。它与 sys_permission 同属"平台定义、租户只读"。
-- ============================================================================

ALTER TABLE `sql_datasource` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sql_datasource_tenant` (`tenant_id`);
ALTER TABLE `sql_define` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sql_define_tenant` (`tenant_id`);
ALTER TABLE `sql_define_param` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sql_define_param_tenant` (`tenant_id`);
ALTER TABLE `sql_field_transform` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sql_field_transform_tenant` (`tenant_id`);
ALTER TABLE `workbench_site` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_workbench_site_tenant` (`tenant_id`);
ALTER TABLE `workbench_token` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_workbench_token_tenant` (`tenant_id`);
ALTER TABLE `ai_config_version` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_config_version_tenant` (`tenant_id`);

-- 存量后台资产与存量后台用户同属运营方（V49 已把 sys_user 归入 __platform__），
-- 否则升级后运营方登录进来会看不到自己建的 SQL 配置与工作台站点。
UPDATE `sql_datasource` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `sql_define` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `sql_define_param` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `sql_field_transform` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `workbench_site` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `workbench_token` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
