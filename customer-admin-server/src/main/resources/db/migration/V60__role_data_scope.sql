-- ============================================================================
-- 数据权限：角色维度的数据范围 + 个人产出物的归属列 + 对话会话归属表
--
-- 目标：除超管与运营方外，页面数据按"当前租户 + 本人"双维度隔离。
-- 租户维度已由 V49/V55 的 tenant_id 与 TenantLineInnerInterceptor 承担，本迁移只补用户维度。
--
-- 为什么是角色上挂范围、而不是在代码里硬判 role_code：
--   硬判等于把"谁能看全部"焊死在版本里，以后加一个"客服主管"角色想看全租户就得改代码重新发版。
--   范围落到角色行上，超管在页面上就能调整，代码只认 ALL/TENANT/SELF 三种语义。
-- ============================================================================

ALTER TABLE `sys_role`
    ADD COLUMN `data_scope` VARCHAR(16) NOT NULL DEFAULT 'SELF'
        COMMENT '数据范围：ALL全部租户 / TENANT本租户全部 / SELF仅本人创建';

-- 存量角色回填：平台运营方看全量，租户管理员看本租户，其余按最小权限落 SELF（列默认值已是 SELF）。
UPDATE `sys_role` SET `data_scope` = 'ALL'    WHERE `role_code` IN ('super_admin', 'operator');
UPDATE `sys_role` SET `data_scope` = 'TENANT' WHERE `role_code` = 'tenant_admin';

-- ============================================================================
-- 个人产出物的归属列
--
-- 只给"确实由某个后台用户产出、且他人不该看见"的表加列，不做无差别铺开：
-- 智能体/知识库/技能/MCP/模型/字典等是租户内共享的配置资产，按创建人过滤会让同租户成员无法协作。
--
-- ai_chat_attachment 是唯一缺归属列的个人产出物（对话里上传的文件，含解析后的正文）。
-- 其余个人产出物已自带归属列：ai_project/ai_project_session/ai_scheduled_task/
-- ai_code_knowledge_index/workbench_site/workbench_token 用 create_by，
-- ai_coding_audit_log/ai_code_review_task/ai_site_message/sys_operation_log 用 user_id。
--
-- 存量行留 NULL：NULL 语义为"租户内共享"，升级后既有页面不会突然空掉，
-- 而此后新产生的数据一律带归属人。
-- ============================================================================

ALTER TABLE `ai_chat_attachment`
    ADD COLUMN `create_by` BIGINT NULL COMMENT '上传人（后台用户ID）；NULL=存量数据，租户内共享',
    ADD INDEX `idx_ai_chat_attachment_create_by` (`create_by`);

-- ============================================================================
-- 对话会话归属表
--
-- ai_chat_session_state 由框架 MysqlAgentStateStore 直接持 DataSource 读写、绕过 MyBatis，
-- 既加不了列（框架不会填）也拦不住 SQL——拦截器对它无能为力（V49 已因此把它列入忽略清单）。
-- 而"别人的对话内容"恰恰是最敏感的页面数据，不能因为框架限制就放弃隔离。
--
-- 因此在 admin 侧单独记一份会话归属：发起对话时写入，会话列表与消息详情按它过滤。
-- 只记归属不记内容，与框架表解耦，框架换实现也不受影响。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ai_chat_session_owner` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `session_id`  VARCHAR(128) NOT NULL COMMENT '会话ID（框架格式 {agentCode}:{uuid}）',
    `agent_code`  VARCHAR(64)  NOT NULL COMMENT '智能体编码',
    `create_by`   BIGINT       NULL COMMENT '会话发起人（后台用户ID）；NULL=存量会话，租户内共享',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次发起时间',
    `tenant_id`   VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    UNIQUE KEY `uk_chat_session_owner_session` (`session_id`),
    KEY `idx_chat_session_owner_agent` (`agent_code`, `create_by`),
    KEY `idx_chat_session_owner_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话归属（框架会话表无法加列的补充）';
