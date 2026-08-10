-- ============================================================================
-- B1 租户地基：租户主数据 + 全量业务表 tenant_id 行级隔离列
--
-- 设计依据见 docs/多租户架构设计.md：
--   - 隔离级别 = 共享库 + tenant_id 行级过滤，强制点在 MyBatis-Plus TenantLineInnerInterceptor；
--   - 原则是"能加列的全加"，忽略表清单越短越安全，故这里只排除三类平台级/框架自建表；
--   - 存量数据归入 default 租户，存量后台用户归入 __platform__（升级前的后台用户都是运营方）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `sys_tenant` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_code`    VARCHAR(64) NOT NULL COMMENT '租户编码（业务主键，出现在 API Key 映射/日志/指标标签里）',
    `tenant_name`    VARCHAR(128) NOT NULL COMMENT '租户名称',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE正常 / SUSPENDED冻结 / TERMINATED退租',
    `contact_name`   VARCHAR(64) COMMENT '联系人',
    `contact_phone`  VARCHAR(32) COMMENT '联系电话',
    `contact_email`  VARCHAR(128) COMMENT '联系邮箱',
    `remark`         VARCHAR(500) COMMENT '备注',
    `expire_time`    DATETIME COMMENT '到期时间（空=不限期）',
    `create_by`      BIGINT COMMENT '创建人ID',
    `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`      BIGINT COMMENT '更新人ID',
    `update_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_sys_tenant_code` (`tenant_code`),
    KEY `idx_sys_tenant_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户主数据';

-- 两个保留租户：default 承接升级前的存量数据，__platform__ 是平台运营方自身
INSERT INTO `sys_tenant` (`tenant_code`, `tenant_name`, `status`, `remark`)
SELECT 'default', '默认租户', 'ACTIVE', '升级前的存量数据归属，等价于原单租户系统'
WHERE NOT EXISTS (SELECT 1 FROM `sys_tenant` WHERE `tenant_code` = 'default');

INSERT INTO `sys_tenant` (`tenant_code`, `tenant_name`, `status`, `remark`)
SELECT '__platform__', '平台运营方', 'ACTIVE', '运营方自身，承载平台级共享配置与全局管理员'
WHERE NOT EXISTS (SELECT 1 FROM `sys_tenant` WHERE `tenant_code` = '__platform__');

-- ============================================================================
-- 全量业务表加 tenant_id（34 张，Flyway 保证只执行一次故无需幂等判定）。
--
-- 未列入的 4 张即拦截器忽略清单（须与 TenantInterceptors.PLATFORM_LEVEL_TABLES 一致）：
--   sys_permission        权限点/菜单树的代码级定义，平台统一，租户只读
--   sys_menu_change_log   菜单变更审计，随 sys_permission 同属平台级
--   ai_system_tool        代码里 @Tool 注册的工具目录（租户是否启用走租户级的 ai_agent_system_tool）
--   ai_chat_session_state 框架 MysqlAgentStateStore 直接持 DataSource 读写，绕过 MyBatis，加了列也没人填
--
-- ai_model_config 在列——它要加列，只是因承载模型凭据而额外由 Service 层做两级可见性（见 §2.4）。
-- 刻意用显式清单而非 information_schema 游标：本库无 DROP TABLE 历史，表集合确定，
-- 而 DELIMITER/存储过程在本项目的 Flyway 迁移里尚无先例，不值得为省几十行去冒解析风险。
-- ============================================================================

ALTER TABLE `ai_agent` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_backup_model` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_backup_model_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_knowledge_base` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_knowledge_base_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_mcp` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_mcp_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_memory` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_memory_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_skill` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_skill_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_sub_agent` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_sub_agent_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_system_tool` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_system_tool_tenant` (`tenant_id`);
ALTER TABLE `ai_agent_task` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_agent_task_tenant` (`tenant_id`);
ALTER TABLE `ai_channel_binding` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_channel_binding_tenant` (`tenant_id`);
ALTER TABLE `ai_channel_robot` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_channel_robot_tenant` (`tenant_id`);
ALTER TABLE `ai_channel_session` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_channel_session_tenant` (`tenant_id`);
ALTER TABLE `ai_chat_attachment` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_chat_attachment_tenant` (`tenant_id`);
ALTER TABLE `ai_code_knowledge_chunk` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_code_knowledge_chunk_tenant` (`tenant_id`);
ALTER TABLE `ai_code_knowledge_index` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_code_knowledge_index_tenant` (`tenant_id`);
ALTER TABLE `ai_code_review_task` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_code_review_task_tenant` (`tenant_id`);
ALTER TABLE `ai_coding_audit_log` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_coding_audit_log_tenant` (`tenant_id`);
ALTER TABLE `ai_knowledge_base` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_knowledge_base_tenant` (`tenant_id`);
ALTER TABLE `ai_mcp` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_mcp_tenant` (`tenant_id`);
ALTER TABLE `ai_model_config` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_model_config_tenant` (`tenant_id`);
ALTER TABLE `ai_project` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_project_tenant` (`tenant_id`);
ALTER TABLE `ai_project_session` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_project_session_tenant` (`tenant_id`);
ALTER TABLE `ai_scheduled_task` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_scheduled_task_tenant` (`tenant_id`);
ALTER TABLE `ai_scheduled_task_run` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_scheduled_task_run_tenant` (`tenant_id`);
ALTER TABLE `ai_site_message` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_site_message_tenant` (`tenant_id`);
ALTER TABLE `ai_skill` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_skill_tenant` (`tenant_id`);
ALTER TABLE `ai_skill_file` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_ai_skill_file_tenant` (`tenant_id`);
ALTER TABLE `cw_agent_call_log` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_cw_agent_call_log_tenant` (`tenant_id`);
ALTER TABLE `cw_agent_call_segment` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_cw_agent_call_segment_tenant` (`tenant_id`);
ALTER TABLE `sys_operation_log` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sys_operation_log_tenant` (`tenant_id`);
ALTER TABLE `sys_role` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sys_role_tenant` (`tenant_id`);
ALTER TABLE `sys_role_permission` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sys_role_permission_tenant` (`tenant_id`);
ALTER TABLE `sys_user` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sys_user_tenant` (`tenant_id`);
ALTER TABLE `sys_user_role` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）', ADD INDEX `idx_sys_user_role_tenant` (`tenant_id`);

-- ============================================================================
-- 存量数据归属修正
-- ============================================================================

-- 升级前的后台用户/角色/权限关联都是运营方的，归入 __platform__；
-- sys_user.username 刻意保持全局唯一（admin 只有一个登录入口，跨租户重名就无法定位归属租户），故不重建唯一键。
UPDATE `sys_user` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `sys_role` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `sys_user_role` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';
UPDATE `sys_role_permission` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';

-- 存量模型配置转为平台共享（租户视角只读且 api_key 脱敏，见 docs/多租户架构设计.md §2.4）
UPDATE `ai_model_config` SET `tenant_id` = '__platform__' WHERE `tenant_id` = 'default';

-- ============================================================================
-- 租户管理菜单与权限点（id 从 220 起，219 是 V46 字典管理占用的最后一个）
--
-- perm_code 一律以 tenant: 开头不是命名巧合：TenantProvisionService 正是按这个前缀
-- 把租户管理权限从租户管理员角色里排除掉的。新增租户相关权限点务必沿用该前缀。
-- 超管无需授权记录（AdminStpInterfaceImpl 对平台租户的 super_admin 直接放行全部权限点）。
-- ============================================================================

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (220, 1, '租户管理', 'tenant:view', 1, '/system/tenant', 'OfficeBuilding', 'library', 9);

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (221, 220, '新增租户', 'tenant:add', 2, 1),
    (222, 220, '编辑租户', 'tenant:edit', 2, 2),
    (223, 220, '删除租户', 'tenant:delete', 2, 3);
