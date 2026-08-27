-- ----------------------------------------------------------------------------
-- customer_admin 全量表结构快照（自动生成，请勿手工编辑）
-- ----------------------------------------------------------------------------
-- 生成方式：scripts/export-schema-snapshot.sh
--           新建临时空库执行 classpath:db/migration 的全部迁移后逐表导出，
--           自增当前值已抹除。
-- 对应版本：Flyway V99
-- 真源：customer-admin-server/src/main/resources/db/migration/
--       改结构一律新增迁移，改本文件不会生效。
-- 用途：结构查阅与全新建库。**不要对已有库执行**，这里没有 IF NOT EXISTS 保护。
--       生产手工初始化仍按 mysql/02-customer-admin/ 的迁移副本顺序执行，
--       那条路径会留下 flyway_schema_history，之后能继续增量升级。
-- 建库：CREATE DATABASE `customer_admin`
--         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- COLLATE 说明：快照里同时出现 utf8mb4_0900_ai_ci 与 utf8mb4_unicode_ci 是既有状况，
--               不是导出错误。MySQL 8 的规则：建表语句写了 DEFAULT CHARSET=utf8mb4
--               却没写 COLLATE 时，用的是该字符集的默认 collation(utf8mb4_0900_ai_ci)
--               而非库的；显式写了 COLLATE 的按其声明；只有既不写 CHARSET 也不写
--               COLLATE 的少数表才继承上面的建库参数——所以导出必须固定按上面的参数
--               建库，换一套参数会让那几张表的输出跟着变。
-- ----------------------------------------------------------------------------

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- ai_agent
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '智能体名称',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '智能体编码（用于动态菜单路由，[a-z0-9-]+）',
  `model_id` bigint NOT NULL COMMENT '关联模型ID（必填）',
  `model_route_policy_id` bigint DEFAULT NULL COMMENT '绑定的 ai_model_route_policy.id；空=沿用主备模型链',
  `system_prompt` text COLLATE utf8mb4_unicode_ci COMMENT '系统提示词',
  `capabilities` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'chat' COMMENT '能力标识（逗号分隔：chat,vibecoding）',
  `icon` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '菜单图标',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0停用 / 1启用',
  `runtime_revision` bigint NOT NULL DEFAULT '0' COMMENT '运行时实例配置修订号',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除（会话历史归档保留）',
  `max_iters` int DEFAULT NULL COMMENT 'ReAct 最大迭代轮数（null=默认10）',
  `tool_timeout_seconds` int DEFAULT NULL COMMENT '工具执行超时秒数（null=框架默认5分钟）',
  `tool_max_attempts` int DEFAULT NULL COMMENT '工具执行最大尝试次数（null=框架默认1次）',
  `compress_trigger_msgs` int DEFAULT NULL COMMENT '上下文压缩触发消息数（null=不启用压缩）',
  `compress_keep_msgs` int DEFAULT NULL COMMENT '压缩后保留最近消息数（null=默认10）',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_agent_code` (`agent_code`),
  KEY `idx_ai_agent_model` (`model_id`),
  KEY `idx_ai_agent_tenant` (`tenant_id`),
  KEY `idx_ai_agent_route_policy` (`tenant_id`,`model_route_policy_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体配置';

-- ----------------------------------------------------------------------------
-- ai_agent_backup_model
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_backup_model` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `model_id` bigint NOT NULL COMMENT '备用模型ID',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '容错切换顺序（升序，越小越先尝试）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_backup` (`agent_id`,`model_id`),
  KEY `idx_backup_model` (`model_id`),
  KEY `idx_ai_agent_backup_model_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-备用模型关联';

-- ----------------------------------------------------------------------------
-- ai_agent_improvement_case
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_improvement_case` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'KNOWLEDGE_GAP/BADCASE',
  `source_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'questionHash或badcaseId',
  `signal_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '同类问题复发观测键',
  `source_signal_count` bigint NOT NULL DEFAULT '0' COMMENT '认领时累计信号数',
  `owner_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sla_due_at_ms` bigint NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent_id` bigint DEFAULT NULL,
  `agent_code` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `artifact_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `artifact_version` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '精确候选版本指纹',
  `candidate_versions_json` json DEFAULT NULL COMMENT '非密钥九维版本绑定',
  `eval_type` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `eval_case_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `eval_run_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reevaluation_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NOT_RUN',
  `reevaluation_verdict` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reevaluation_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `publish_task_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `publish_revision` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `publish_status` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `published_at_ms` bigint DEFAULT NULL COMMENT 'Worker观测到全目标APPLIED的时间',
  `baseline_signal_count` bigint DEFAULT NULL,
  `observation_started_at_ms` bigint DEFAULT NULL,
  `observation_ends_at_ms` bigint DEFAULT NULL,
  `min_exposure_calls` int DEFAULT NULL,
  `max_recurrence_signals` int DEFAULT NULL,
  `observed_calls` bigint NOT NULL DEFAULT '0',
  `observed_signals` bigint NOT NULL DEFAULT '0',
  `effect_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NOT_STARTED',
  `last_observed_at_ms` bigint DEFAULT NULL,
  `next_action_at_ms` bigint NOT NULL,
  `lease_owner` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until_ms` bigint NOT NULL DEFAULT '0',
  `automation_failures` int NOT NULL DEFAULT '0',
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at_ms` bigint NOT NULL,
  `updated_at_ms` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_improvement_source` (`tenant_id`,`source_type`,`source_key`),
  KEY `idx_improvement_owner_sla` (`tenant_id`,`owner_id`,`status`,`sla_due_at_ms`),
  KEY `idx_improvement_due` (`status`,`next_action_at_ms`,`lease_until_ms`),
  KEY `idx_improvement_publish` (`tenant_id`,`publish_task_id`),
  CONSTRAINT `chk_improvement_source_type` CHECK ((`source_type` in (_utf8mb4'KNOWLEDGE_GAP',_utf8mb4'BADCASE'))),
  CONSTRAINT `chk_improvement_status` CHECK ((`status` in (_utf8mb4'OWNED',_utf8mb4'READY_FOR_REEVALUATION',_utf8mb4'REEVALUATING',_utf8mb4'REEVALUATION_FAILED',_utf8mb4'READY_TO_PUBLISH',_utf8mb4'PUBLISHING',_utf8mb4'PUBLISH_FAILED',_utf8mb4'OBSERVING',_utf8mb4'VERIFIED',_utf8mb4'INEFFECTIVE',_utf8mb4'INCONCLUSIVE',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体问题从认领到线上效果验证的治理闭环';

-- ----------------------------------------------------------------------------
-- ai_agent_knowledge_base
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_knowledge_base` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `knowledge_base_id` bigint NOT NULL COMMENT '知识库ID',
  `knowledge_base_version_id` bigint NOT NULL COMMENT 'Agent 冻结的知识库版本ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_agent_kb` (`agent_id`,`knowledge_base_id`),
  KEY `idx_ai_agent_kb_base` (`knowledge_base_id`),
  KEY `idx_ai_agent_knowledge_base_tenant` (`tenant_id`),
  KEY `idx_ai_agent_kb_version` (`tenant_id`,`knowledge_base_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-知识库关联';

-- ----------------------------------------------------------------------------
-- ai_agent_mcp
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_mcp` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `mcp_id` bigint NOT NULL COMMENT 'MCP ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_agent_mcp` (`agent_id`,`mcp_id`),
  KEY `idx_ai_agent_mcp_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-MCP关联';

-- ----------------------------------------------------------------------------
-- ai_agent_memory
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_code` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '智能体与可信调用主体分区键',
  `content` longtext COLLATE utf8mb4_unicode_ci COMMENT '长期记忆内容（MEMORY.md 全文）',
  `version` bigint NOT NULL DEFAULT '1' COMMENT '乐观锁版本',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_code` (`agent_code`),
  KEY `idx_ai_agent_memory_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体跨会话长期记忆（MEMORY.md 权威存储）';

-- ----------------------------------------------------------------------------
-- ai_agent_skill
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `skill_id` bigint NOT NULL COMMENT 'Skill ID',
  `skill_version_id` bigint NOT NULL COMMENT 'Agent 冻结的 Skill 版本ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_agent_skill` (`agent_id`,`skill_id`),
  KEY `idx_ai_agent_skill_tenant` (`tenant_id`),
  KEY `idx_ai_agent_skill_version` (`tenant_id`,`skill_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-Skill关联';

-- ----------------------------------------------------------------------------
-- ai_agent_sub_agent
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_sub_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '父智能体ID',
  `sub_agent_id` bigint NOT NULL COMMENT '子智能体ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_agent_sub_agent` (`agent_id`,`sub_agent_id`),
  KEY `idx_ai_agent_sub_agent_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-子智能体关联';

-- ----------------------------------------------------------------------------
-- ai_agent_system_tool
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_system_tool` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `system_tool_id` bigint NOT NULL COMMENT '系统工具ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_agent_system_tool_agent` (`agent_id`),
  KEY `idx_ai_agent_system_tool_tool` (`system_tool_id`),
  KEY `idx_ai_agent_system_tool_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-系统工具关联（纯关系表）';

-- ----------------------------------------------------------------------------
-- ai_agent_task
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_agent_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '框架任务ID（agent_spawn 返回给模型的那个，全局唯一）',
  `parent_agent_code` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '发起任务的父智能体编码',
  `sub_agent_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '执行任务的子智能体标识',
  `parent_session_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '父会话ID（任务归属的对话）',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
  `result` mediumtext COLLATE utf8mb4_general_ci COMMENT '任务成功时的结果文本',
  `error_message` text COLLATE utf8mb4_general_ci COMMENT '任务失败时的错误信息',
  `cancel_requested` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已请求取消（1=是）',
  `owner_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '当前执行所有者（Pod/进程唯一ID）',
  `lease_until` datetime(3) DEFAULT NULL COMMENT '所有权租约到期时间',
  `heartbeat_at` datetime(3) DEFAULT NULL COMMENT '当前所有者最近心跳',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '领取执行次数',
  `replayable` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否具备可重放执行输入',
  `task_input` mediumtext COLLATE utf8mb4_general_ci COMMENT '子智能体原始任务提示词',
  `child_session_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '恢复执行的稳定子会话ID',
  `runtime_user_id` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'RuntimeContext userId',
  `subject_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可信调用主体类型',
  `subject_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可信调用主体ID或指纹',
  `subject_authenticated` tinyint(1) DEFAULT NULL COMMENT '主体是否已认证',
  `access_epoch` bigint DEFAULT NULL COMMENT '租户访问版本快照',
  `channel_code` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '服务端确认的调用渠道',
  `created_at` datetime NOT NULL COMMENT '任务创建时间',
  `started_at` datetime DEFAULT NULL COMMENT '开始执行时间（进入 RUNNING）',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间（进入任一终态）',
  `updated_at` datetime NOT NULL COMMENT '最后更新时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_session` (`parent_session_id`),
  KEY `idx_agent_status` (`parent_agent_code`,`status`),
  KEY `idx_created` (`created_at`),
  KEY `idx_ai_agent_task_tenant` (`tenant_id`),
  KEY `idx_task_lease_recovery` (`status`,`replayable`,`lease_until`,`attempt_count`),
  KEY `idx_task_owner_status` (`owner_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='智能体后台委派任务';

-- ----------------------------------------------------------------------------
-- ai_channel_binding
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_channel_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '渠道编码（唯一，如 default/wechat/web）',
  `agent_id` bigint NOT NULL COMMENT '绑定的智能体ID（其配置作为客服机器人运行时配置）',
  `status` int NOT NULL DEFAULT '1' COMMENT '0停用 / 1启用（停用则不参与自动发布）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` int NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否/1是',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_code` (`channel_code`,`deleted`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_ai_channel_binding_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道-客服机器人运行配置绑定';

-- ----------------------------------------------------------------------------
-- ai_channel_robot
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_channel_robot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '渠道类型：dingtalk（预留 wecom/wechat）',
  `robot_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '机器人名称',
  `app_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '渠道 AppKey / ClientId',
  `app_secret_cipher` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AppSecret（AES-GCM 密文，永不明文返回列表）',
  `robot_code` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '机器人编码（钉钉 robotCode 等，选填）',
  `callback_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'plaintext' COMMENT '微信回调模式：plaintext 明文 / safe AES 安全模式',
  `encoding_aes_key_cipher` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '微信 EncodingAESKey（AES-GCM 密文）',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '绑定的智能体编码（ai_agent.agent_code）',
  `session_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'continuous' COMMENT '会话模式：continuous 持续会话 / per_message 单次问答',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0停用 / 1启用',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_appkey` (`channel_type`,`app_key`),
  KEY `idx_ai_channel_robot_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道机器人';

-- ----------------------------------------------------------------------------
-- ai_channel_session
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_channel_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '渠道类型：dingtalk（预留 wecom/wechat）',
  `app_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '渠道 AppKey / ClientId',
  `external_user_id` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '渠道侧外部用户唯一标识（钉钉 senderStaffId 等）',
  `session_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '映射到的工作区会话 ID（ch-<uuid>）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_user` (`channel_type`,`app_key`,`external_user_id`),
  KEY `idx_ai_channel_session_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道外部用户与工作区会话映射';

-- ----------------------------------------------------------------------------
-- ai_chat_attachment
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_chat_attachment` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '附件ID(UUID)',
  `session_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '会话ID',
  `message_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '绑定的用户消息ID（框架Msg.id，空=未绑定）',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '智能体编码（一次上传归属的智能体）',
  `uploader` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '上传者标识（当前登录管理员ID）',
  `channel` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '来源渠道：admin_chat/vibecoding',
  `file_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  `extension` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '扩展名',
  `mime_type` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'MIME类型',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `storage_path` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '落盘相对路径',
  `parse_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态：SUCCESS/FAILED',
  `parsed_text` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '解析出的文本（FAILED 为空）',
  `error_message` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '解析失败原因（SUCCESS 为空）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_by` bigint DEFAULT NULL COMMENT '上传人（后台用户ID）；NULL=存量数据，租户内共享',
  PRIMARY KEY (`id`),
  KEY `idx_ai_attachment_session` (`session_id`),
  KEY `idx_ai_attachment_agent` (`agent_code`),
  KEY `idx_ai_attachment_created` (`created_at`),
  KEY `idx_ai_chat_attachment_tenant` (`tenant_id`),
  KEY `idx_ai_chat_attachment_create_by` (`create_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话附件（多格式解析落库）';

-- ----------------------------------------------------------------------------
-- ai_chat_session_state
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_chat_session_state` (
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `state_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `item_index` int NOT NULL DEFAULT '0',
  `state_data` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`,`state_key`,`item_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话状态持久化（AgentStateStore，含短期记忆/对话历史）';

-- ----------------------------------------------------------------------------
-- ai_code_knowledge_chunk
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_code_knowledge_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `index_id` bigint NOT NULL COMMENT '所属索引ID',
  `source_path` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源文件相对路径',
  `chunk_index` int NOT NULL COMMENT '同一文件内的分块序号（0起）',
  `symbol` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分块对应的符号（类/方法名等，可空）',
  `lang` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '语言标识（java/xml/...）',
  `content` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分块文本内容',
  `embedding` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '向量（JSON 浮点数组）',
  `dimensions` int NOT NULL COMMENT '向量维度',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_ai_code_knowledge_chunk_index` (`index_id`),
  KEY `idx_ai_code_knowledge_chunk_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码知识库分块与向量';

-- ----------------------------------------------------------------------------
-- ai_code_knowledge_index
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_code_knowledge_index` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `index_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '索引名（唯一，用户可读标识）',
  `source_path` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '被索引的源码目录/文件路径',
  `embedding_model` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Embedding 模型名（如 text-embedding-v3）',
  `dimensions` int DEFAULT NULL COMMENT '向量维度',
  `chunk_count` int NOT NULL DEFAULT '0' COMMENT '已入库分块数（构建进度）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：BUILDING 构建中 / READY 就绪 / FAILED 失败',
  `message` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态说明（失败原因等）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_code_knowledge_index_name` (`index_name`),
  KEY `idx_ai_code_knowledge_index_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码知识库索引';

-- ----------------------------------------------------------------------------
-- ai_code_review_task
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_code_review_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_code` varchar(64) NOT NULL COMMENT '智能体编码',
  `session_id` varchar(64) NOT NULL COMMENT '会话 id',
  `user_id` bigint NOT NULL COMMENT '提交人（admin 用户 id）',
  `status` varchar(20) NOT NULL COMMENT '状态：RUNNING/SUCCESS/FAILED',
  `result_json` mediumtext COMMENT '审查结果 JSON（SUCCESS 才有）',
  `error_msg` varchar(1000) DEFAULT NULL COMMENT '失败原因（FAILED 才有）',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `finish_time` datetime DEFAULT NULL COMMENT '完成时间（SUCCESS/FAILED 时写入）',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_ai_code_review_task_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 代码审查异步任务';

-- ----------------------------------------------------------------------------
-- ai_coding_audit_log
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_coding_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '操作人用户ID',
  `username` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人账号（冗余存储，用户改名/删除后历史仍可读）',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '智能体编码',
  `session_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话ID',
  `operation` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型：CHAT_STREAM/FILE_SAVE/GIT_DIFF_SUMMARY/COMMIT_MESSAGE/PR_DESCRIPTION',
  `changed_files` text COLLATE utf8mb4_unicode_ci COMMENT '本次操作产生变更的文件清单（JSON数组），只读操作/无变更为空',
  `input_tokens` int DEFAULT NULL COMMENT '模型输入token数（未发生模型调用或框架未返回用量时为空）',
  `output_tokens` int DEFAULT NULL COMMENT '模型输出token数',
  `total_tokens` int DEFAULT NULL COMMENT '模型总token数',
  `cached_tokens` bigint DEFAULT NULL COMMENT '命中缓存的输入token（input_tokens的子集，不计入total_tokens）',
  `duration_ms` bigint DEFAULT NULL COMMENT '操作耗时（毫秒）',
  `result` tinyint NOT NULL COMMENT '结果：1成功 / 0失败',
  `error_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败错误码（ResultCode枚举名或流终止信号），成功为空',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_ai_coding_audit_agent_session` (`agent_code`,`session_id`),
  KEY `idx_ai_coding_audit_user` (`user_id`),
  KEY `idx_ai_coding_audit_time` (`create_time`),
  KEY `idx_ai_coding_audit_log_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI编码操作审计日志';

-- ----------------------------------------------------------------------------
-- ai_config_version
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_config_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `config_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置类型：AGENT 智能体运行时配置 / MODEL 模型配置',
  `target_code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标业务编码（如 agentCode / channelCode），人可读、跨环境稳定',
  `target_id` bigint DEFAULT NULL COMMENT '目标主键（可空：跨环境迁移后主键会变，故以 target_code 为准）',
  `version` int NOT NULL COMMENT '该目标下的版本序号，从 1 开始',
  `content` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '下发内容的完整快照（JSON），回滚即取此内容重发',
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '内容摘要，用于跳过"内容没变却重复发布"',
  `publish_scope` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'FULL' COMMENT '发布范围：FULL 全量 / GRAY 灰度',
  `gray_tenants` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '灰度租户编码列表（JSON 数组），publish_scope=GRAY 时有效',
  `data_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '实际发布到的 Nacos dataId',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PUBLISHED' COMMENT '状态：PUBLISHED 已发布 / SUPERSEDED 已被后续版本取代 / FAILED 发布失败',
  `source_version` int DEFAULT NULL COMMENT '回滚来源版本号；非回滚产生的版本为空',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发布说明',
  `create_by` bigint DEFAULT NULL COMMENT '发布人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_version_tenant` (`tenant_id`,`config_type`,`target_code`,`version`),
  KEY `idx_config_version_target` (`config_type`,`target_code`,`create_time`),
  KEY `idx_config_version_status` (`status`),
  KEY `idx_ai_config_version_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置发布版本快照（支持对比与回滚）';

-- ----------------------------------------------------------------------------
-- ai_cost_alert
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_cost_alert` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `period` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '周期：DAILY/MONTHLY',
  `period_key` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '自然周期键，如 2026-08 或 2026-08-21',
  `alert_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'BUDGET_WARNING/BUDGET_EXCEEDED/FORECAST_EXCEEDED',
  `used_amount` decimal(16,4) NOT NULL DEFAULT '0.0000' COMMENT '首次触发时已结算金额（元）',
  `limit_amount` decimal(16,4) NOT NULL DEFAULT '0.0000' COMMENT '触发时金额预算（元）',
  `forecast_amount` decimal(16,4) NOT NULL DEFAULT '0.0000' COMMENT '触发时周期预测金额（元）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKED',
  `first_seen_at` datetime(3) NOT NULL COMMENT '首次触发时间',
  `ack_by` bigint DEFAULT NULL COMMENT '确认人ID',
  `ack_at` datetime(3) DEFAULT NULL COMMENT '确认时间',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_alert_business` (`tenant_id`,`period`,`period_key`,`alert_type`),
  KEY `idx_cost_alert_tenant_status` (`tenant_id`,`status`,`first_seen_at`),
  KEY `idx_cost_alert_status_time` (`status`,`first_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='金额预算告警事实';

-- ----------------------------------------------------------------------------
-- ai_eval_release_gate_override
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_eval_release_gate_override` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `task_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '可靠发布任务ID',
  `candidate_content_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '豁免对应的候选内容哈希',
  `operator_id` bigint NOT NULL COMMENT '豁免操作人',
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '紧急豁免原因',
  `previous_decision_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '豁免前的完整门禁判定',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_eval_gate_override_task` (`tenant_id`,`task_id`),
  KEY `idx_eval_gate_override_operator` (`tenant_id`,`operator_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测发布门禁紧急豁免审计';

-- ----------------------------------------------------------------------------
-- ai_eval_release_gate_policy
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_eval_release_gate_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `eval_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'INTENT/QUALITY',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否参与发布门禁',
  `min_primary_metric` double DEFAULT NULL COMMENT '主指标绝对下限',
  `min_secondary_metric` double DEFAULT NULL COMMENT '次指标绝对下限',
  `max_primary_regression` double DEFAULT NULL COMMENT '相对基线允许的主指标最大下降',
  `max_secondary_regression` double DEFAULT NULL COMMENT '相对基线允许的次指标最大下降',
  `critical_case_ids_json` text COLLATE utf8mb4_unicode_ci COMMENT '零容忍关键用例ID数组',
  `judge_error_policy` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/ALLOW',
  `require_artifact_match` tinyint NOT NULL DEFAULT '1' COMMENT '评测版本是否必须匹配发布候选',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_eval_gate_policy_tenant_type` (`tenant_id`,`eval_type`),
  KEY `idx_eval_gate_policy_enabled` (`tenant_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测发布门禁策略';

-- ----------------------------------------------------------------------------
-- ai_governance_audit_event
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_governance_audit_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `request_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批请求ID',
  `sequence_no` int NOT NULL COMMENT '请求内单调事件序号',
  `event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUBMITTED/APPROVED/EXECUTED/REJECTED/FAILED/EXPIRED',
  `actor_id` bigint DEFAULT NULL COMMENT '操作人；系统事件为空',
  `actor_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人账号快照',
  `payload_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批载荷 SHA-256',
  `detail` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '不含敏感数据的审计摘要',
  `previous_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '前一事件哈希',
  `event_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '当前事件哈希',
  `retention_until` datetime NOT NULL COMMENT '最短留存截止时间',
  `create_time` datetime(3) NOT NULL COMMENT '事件时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_governance_audit_request_seq` (`tenant_id`,`request_id`,`sequence_no`),
  UNIQUE KEY `uk_governance_audit_event_hash` (`event_hash`),
  KEY `idx_governance_audit_retention` (`retention_until`),
  KEY `idx_governance_audit_request` (`tenant_id`,`request_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='追加写治理审计哈希链';

-- ----------------------------------------------------------------------------
-- ai_governed_change_request
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_governed_change_request` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批请求ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `change_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '类型化高风险变更',
  `target_key` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '稳定目标键',
  `payload_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务端类型化执行载荷，不含凭据',
  `payload_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '执行载荷 SHA-256',
  `maker_id` bigint NOT NULL COMMENT '发起人',
  `maker_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发起人账号快照',
  `checker_id` bigint DEFAULT NULL COMMENT '复核人',
  `checker_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '复核人账号快照',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PENDING/EXECUTING/EXECUTED/REJECTED/FAILED/EXPIRED',
  `decision_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '复核理由',
  `result_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '脱敏执行结果',
  `failure_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '稳定失败码，不保存异常明文',
  `expires_at` datetime(3) NOT NULL COMMENT '审批到期时间',
  `decided_at` datetime(3) DEFAULT NULL COMMENT '复核时间',
  `executed_at` datetime(3) DEFAULT NULL COMMENT '执行终止时间',
  `create_time` datetime(3) NOT NULL COMMENT '创建时间',
  `update_time` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_governed_change_tenant_status` (`tenant_id`,`status`,`create_time` DESC),
  KEY `idx_governed_change_expiry` (`status`,`expires_at`),
  KEY `idx_governed_change_execution_recovery` (`status`,`update_time`),
  KEY `idx_governed_change_target` (`tenant_id`,`change_type`,`target_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='maker-checker 高风险变更请求';

-- ----------------------------------------------------------------------------
-- ai_knowledge_base
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_base` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kb_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库名称（唯一标识，供智能体表单展示）',
  `base_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'RAG 服务基址（不含 /api/v1/knowledge/search 路径）',
  `app_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库应用ID（请求体 app_id）',
  `api_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AppKey（AES/GCM 加密存储，请求头 Authorization: Bearer）',
  `content_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'application/json' COMMENT '请求 Content-Type',
  `extra_headers` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '自定义请求头（JSON 对象字符串，空=无）',
  `top_n` int NOT NULL DEFAULT '5' COMMENT '单次检索返回条数（请求体 top_n）',
  `score_threshold` decimal(6,4) NOT NULL DEFAULT '0.0000' COMMENT '相关度阈值：低于该值的召回丢弃，0=不过滤（rerank 分数量级仅 0.1x）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 / 1启用',
  `test_status` tinyint NOT NULL DEFAULT '0' COMMENT '最近测试结果：0未测试 / 1成功 / 2失败',
  `test_time` datetime DEFAULT NULL COMMENT '最近测试时间',
  `remark` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `current_version_id` bigint DEFAULT NULL COMMENT '当前不可变版本ID',
  `latest_version_no` int NOT NULL DEFAULT '0' COMMENT '最新版本号',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kb_name` (`kb_name`),
  KEY `idx_ai_knowledge_base_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库配置';

-- ----------------------------------------------------------------------------
-- ai_knowledge_base_version
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_base_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `knowledge_base_id` bigint NOT NULL COMMENT '稳定知识库ID',
  `version_no` int NOT NULL COMMENT '版本号',
  `base_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本冻结 RAG 服务基址',
  `app_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本冻结应用ID',
  `api_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本冻结密文 AppKey',
  `content_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'application/json' COMMENT '版本冻结 Content-Type',
  `extra_headers` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '版本冻结请求头',
  `top_n` int NOT NULL DEFAULT '5' COMMENT '版本冻结召回数',
  `score_threshold` decimal(8,6) NOT NULL DEFAULT '0.000000' COMMENT '版本冻结相关度阈值',
  `checkpoint` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文档快照 checkpoint',
  `snapshot_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置与文档成员指纹',
  `document_count` int NOT NULL DEFAULT '0' COMMENT '快照文档数',
  `quality_score` decimal(8,6) NOT NULL DEFAULT '1.000000' COMMENT '快照质量分',
  `quality_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PASSED' COMMENT 'UNKNOWN/PASSED/FAILED',
  `change_note` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '变更说明',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kb_version_no` (`tenant_id`,`knowledge_base_id`,`version_no`),
  KEY `idx_ai_kb_version_base` (`tenant_id`,`knowledge_base_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库不可变版本';

-- ----------------------------------------------------------------------------
-- ai_knowledge_base_version_document
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_base_version_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `knowledge_base_version_id` bigint NOT NULL COMMENT '知识库版本ID',
  `document_revision_id` bigint NOT NULL COMMENT '文档修订ID',
  `source_id` bigint NOT NULL COMMENT '文档源ID',
  `external_id` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档稳定外部ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kb_version_document` (`knowledge_base_version_id`,`source_id`,`external_id`),
  KEY `idx_ai_kb_version_revision` (`tenant_id`,`document_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库版本文档成员';

-- ----------------------------------------------------------------------------
-- ai_knowledge_document
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `knowledge_base_id` bigint NOT NULL COMMENT '知识库ID',
  `source_id` bigint NOT NULL COMMENT '文档源ID',
  `external_id` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '上游文档稳定ID',
  `current_revision_id` bigint DEFAULT NULL COMMENT '当前修订ID',
  `source_version` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上游版本',
  `content_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前正文指纹',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '同步删除标记',
  `source_updated_at` datetime(6) DEFAULT NULL COMMENT '上游更新时间',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kb_document_external` (`tenant_id`,`source_id`,`external_id`),
  KEY `idx_ai_kb_document_active` (`tenant_id`,`knowledge_base_id`,`deleted`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档稳定身份';

-- ----------------------------------------------------------------------------
-- ai_knowledge_document_chunk
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_document_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `document_revision_id` bigint NOT NULL COMMENT '文档修订ID',
  `chunk_index` int NOT NULL COMMENT '分块序号',
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分块正文',
  `embedding` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '向量 JSON',
  `dimensions` int NOT NULL COMMENT '向量维度',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kb_chunk_index` (`document_revision_id`,`chunk_index`),
  KEY `idx_ai_kb_chunk_tenant_revision` (`tenant_id`,`document_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档向量分块';

-- ----------------------------------------------------------------------------
-- ai_knowledge_document_revision
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_document_revision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `document_id` bigint NOT NULL COMMENT '文档稳定ID',
  `source_id` bigint NOT NULL COMMENT '文档源ID',
  `parent_revision_id` bigint DEFAULT NULL COMMENT '父修订ID',
  `operation` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'UPSERT/DELETE',
  `source_version` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上游版本',
  `title` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标题',
  `source_uri` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源地址',
  `content` longtext COLLATE utf8mb4_unicode_ci COMMENT '不可变正文；DELETE 修订为空',
  `content_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '正文指纹',
  `acl_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/RESTRICTED',
  `allowed_subject_types` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '允许主体类型',
  `allowed_subject_ids` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '允许主体ID JSON数组',
  `allowed_channels` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '允许渠道 JSON数组',
  `source_updated_at` datetime(6) DEFAULT NULL COMMENT '上游更新时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_kb_revision_document` (`tenant_id`,`document_id`,`id`),
  KEY `idx_ai_kb_revision_source` (`tenant_id`,`source_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档不可变修订';

-- ----------------------------------------------------------------------------
-- ai_knowledge_source
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_source` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `knowledge_base_id` bigint NOT NULL COMMENT '知识库ID',
  `source_code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档源稳定编码',
  `source_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档源名称',
  `source_type` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PUSH' COMMENT '接入类型',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0禁用/1启用',
  `freshness_sla_minutes` int NOT NULL DEFAULT '1440' COMMENT '新鲜度 SLA 分钟',
  `quality_threshold` decimal(8,6) NOT NULL DEFAULT '0.800000' COMMENT '最低质量分',
  `default_acl_json` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '默认文档 ACL',
  `current_checkpoint` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近成功 checkpoint',
  `last_sync_at` datetime(6) DEFAULT NULL COMMENT '最近同步时间',
  `last_successful_sync_at` datetime(6) DEFAULT NULL COMMENT '最近成功同步时间',
  `last_sync_status` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'PROCESSING/SUCCEEDED/FAILED/QUALITY_FAILED',
  `last_sync_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近同步错误',
  `active_document_count` int NOT NULL DEFAULT '0' COMMENT '有效文档数',
  `quality_score` decimal(8,6) DEFAULT NULL COMMENT '最近质量分',
  `quality_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
  `revision` int NOT NULL DEFAULT '1' COMMENT '配置修订号',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kb_source_code` (`tenant_id`,`knowledge_base_id`,`source_code`),
  KEY `idx_ai_kb_source_base` (`tenant_id`,`knowledge_base_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档源';

-- ----------------------------------------------------------------------------
-- ai_knowledge_sync_run
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_knowledge_sync_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `knowledge_base_id` bigint NOT NULL COMMENT '知识库ID',
  `source_id` bigint NOT NULL COMMENT '文档源ID',
  `request_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '上游幂等请求ID',
  `request_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '请求内容指纹',
  `sync_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'FULL/INCREMENTAL',
  `checkpoint_before` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交前 checkpoint',
  `checkpoint_after` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标 checkpoint',
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PROCESSING/SUCCEEDED/FAILED/QUALITY_FAILED',
  `received_count` int NOT NULL DEFAULT '0' COMMENT '接收变更数',
  `upserted_count` int DEFAULT NULL COMMENT '写入数',
  `deleted_count` int DEFAULT NULL COMMENT '删除数',
  `unchanged_count` int DEFAULT NULL COMMENT '未变化数',
  `active_document_count` int DEFAULT NULL COMMENT '提交后有效文档数',
  `duplicate_content_count` int DEFAULT NULL COMMENT '重复正文数',
  `quality_score` decimal(8,6) DEFAULT NULL COMMENT '质量分',
  `quality_status` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'UNKNOWN/PASSED/FAILED',
  `knowledge_base_version_id` bigint DEFAULT NULL COMMENT '成功发布的知识库版本ID',
  `snapshot_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成功快照指纹',
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败摘要',
  `started_at` datetime(6) NOT NULL COMMENT '开始时间',
  `finished_at` datetime(6) DEFAULT NULL COMMENT '结束时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kb_sync_request` (`tenant_id`,`source_id`,`request_id`),
  KEY `idx_ai_kb_sync_runs` (`tenant_id`,`source_id`,`id`),
  KEY `idx_ai_kb_sync_status` (`tenant_id`,`status`,`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档源同步运行';

-- ----------------------------------------------------------------------------
-- ai_mcp
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_mcp` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `mcp_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MCP 名称',
  `mcp_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '类型（stdio / sse）',
  `config` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MCP 连接配置（命令/URL/参数等，JSON）',
  `secret_ref_id` bigint DEFAULT NULL COMMENT 'MCP 敏感配置 SecretRef',
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 / 1启用',
  `test_status` tinyint NOT NULL DEFAULT '0' COMMENT '连通性测试：0未测试 / 1成功 / 2失败',
  `test_time` datetime DEFAULT NULL COMMENT '最近一次测试时间',
  `allowed_subject_types` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER,ADMIN_USER,API_KEY' COMMENT '允许调用主体类型，逗号分隔：USER/ADMIN_USER/IP/API_KEY',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_ai_mcp_tenant` (`tenant_id`),
  KEY `idx_ai_mcp_secret_ref` (`secret_ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP 配置';

-- ----------------------------------------------------------------------------
-- ai_model_asset
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `asset_code` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户内稳定资产编码',
  `asset_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资产名称',
  `vendor` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CUSTOM' COMMENT '模型厂商，不等同于接入协议',
  `model_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '厂商模型标识',
  `family` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型家族',
  `asset_version` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型版本',
  `modality` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'TEXT' COMMENT '能力模态，逗号分隔',
  `context_window` int DEFAULT NULL COMMENT '上下文窗口 token 数',
  `max_output_tokens` int DEFAULT NULL COMMENT '最大输出 token 数',
  `supports_stream` tinyint NOT NULL DEFAULT '1' COMMENT '是否支持流式输出',
  `supports_tool` tinyint NOT NULL DEFAULT '1' COMMENT '是否支持工具调用',
  `supports_json_schema` tinyint NOT NULL DEFAULT '0' COMMENT '是否支持 JSON Schema',
  `supports_multimodal` tinyint NOT NULL DEFAULT '0' COMMENT '是否支持多模态',
  `capability_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '能力声明摘要',
  `lifecycle_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/DEPRECATED/RETIRED',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '修改人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_asset_tenant_code` (`tenant_id`,`asset_code`,`deleted`),
  KEY `idx_model_asset_tenant_model` (`tenant_id`,`vendor`,`model_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型目录资产';

-- ----------------------------------------------------------------------------
-- ai_model_certification
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_certification` (
  `model_config_id` bigint NOT NULL COMMENT '模型部署ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
  `current_run_id` bigint DEFAULT NULL COMMENT '当前认证运行ID',
  `certified_endpoint_revision` int DEFAULT NULL COMMENT '通过认证的端点修订号',
  `certified_secret_version` int DEFAULT NULL COMMENT '通过认证的 SecretRef 版本',
  `valid_until` datetime DEFAULT NULL COMMENT '认证到期时间',
  `completed_at` datetime DEFAULT NULL COMMENT '最近完成时间',
  `passed_checks` int NOT NULL DEFAULT '0' COMMENT '通过检查数',
  `failed_checks` int NOT NULL DEFAULT '0' COMMENT '失败检查数',
  `latency_p95_ms` bigint DEFAULT NULL COMMENT '最近基础 P95 延迟',
  `verified_context_tokens` int DEFAULT NULL COMMENT '最近确认上下文窗口',
  `failure_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近失败编码',
  `failure_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近脱敏失败摘要',
  `revision` int NOT NULL DEFAULT '1' COMMENT '快照修订号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`model_config_id`),
  KEY `idx_model_certification_tenant_status` (`tenant_id`,`status`,`valid_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署认证快照';

-- ----------------------------------------------------------------------------
-- ai_model_certification_run
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_certification_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `model_config_id` bigint NOT NULL COMMENT '模型部署ID',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PASSED/FAILED',
  `endpoint_revision` int NOT NULL COMMENT '认证时端点修订号',
  `secret_version` int DEFAULT NULL COMMENT '认证时 SecretRef 版本；不保存凭据',
  `required_context_tokens` int NOT NULL COMMENT '要求的上下文窗口 token 数',
  `max_latency_ms` bigint NOT NULL COMMENT '基础延迟门槛',
  `max_input_price` decimal(16,6) NOT NULL COMMENT '输入单价上限/百万 token',
  `max_output_price` decimal(16,6) NOT NULL COMMENT '输出单价上限/百万 token',
  `latency_p95_ms` bigint DEFAULT NULL COMMENT '基础探测 P95 延迟',
  `verified_context_tokens` int DEFAULT NULL COMMENT '运行时与资产声明共同确认的窗口',
  `input_price` decimal(16,6) DEFAULT NULL COMMENT '认证时生效输入单价',
  `output_price` decimal(16,6) DEFAULT NULL COMMENT '认证时生效输出单价',
  `currency` varchar(8) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单价币种',
  `checks_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整检查项与证据 JSON，不含凭据',
  `failure_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '首个失败检查编码',
  `failure_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '脱敏失败摘要',
  `triggered_by` bigint DEFAULT NULL COMMENT '触发人',
  `started_at` datetime NOT NULL COMMENT '开始时间',
  `completed_at` datetime NOT NULL COMMENT '完成时间',
  `valid_until` datetime DEFAULT NULL COMMENT 'PASSED 认证有效期',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_model_cert_run_tenant_model` (`tenant_id`,`model_config_id`,`completed_at` DESC),
  KEY `idx_model_cert_run_status` (`tenant_id`,`status`,`valid_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署认证不可变运行记录';

-- ----------------------------------------------------------------------------
-- ai_model_config
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名称（自定义标识）',
  `deployment_code` varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户内稳定部署编码',
  `provider` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'openai' COMMENT '提供方（当前固定 openai，预留扩展）',
  `protocol_adapter` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '运行时接入协议',
  `api_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AppKey（AES/GCM 加密存储）',
  `secret_ref_id` bigint DEFAULT NULL COMMENT '凭据引用ID',
  `base_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '接口 URL',
  `region` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部署地域',
  `environment` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PRODUCTION' COMMENT '部署环境',
  `endpoint_revision` int NOT NULL DEFAULT '1' COMMENT '端点配置修订号',
  `lifecycle_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DEPRECATED/RETIRED',
  `certification_required` tinyint NOT NULL DEFAULT '0' COMMENT '0=存量兼容免认证/1=上线前必须通过认证',
  `model` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名（如 gpt-4o）',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '是否默认模型：0否 / 1是',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 / 1启用',
  `test_status` tinyint NOT NULL DEFAULT '0' COMMENT '最近测试结果：0未测试 / 1成功 / 2失败',
  `test_time` datetime DEFAULT NULL COMMENT '最近测试时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `asset_id` bigint DEFAULT NULL COMMENT '模型目录资产ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_deployment_tenant_code` (`tenant_id`,`deployment_code`,`deleted`),
  KEY `idx_ai_model_config_tenant` (`tenant_id`),
  KEY `idx_model_config_asset` (`tenant_id`,`asset_id`),
  KEY `idx_model_config_secret` (`tenant_id`,`secret_ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型配置';

-- ----------------------------------------------------------------------------
-- ai_model_experiment
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_experiment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `experiment_code` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户内稳定实验编码',
  `experiment_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '实验名称',
  `agent_id` bigint NOT NULL COMMENT '实验智能体ID',
  `control_deployment_id` bigint NOT NULL COMMENT '对照组模型部署ID',
  `control_model_ref` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '创建时对照组模型标识快照',
  `control_endpoint_revision` int NOT NULL COMMENT '创建时对照组端点修订号',
  `treatment_deployment_id` bigint NOT NULL COMMENT '实验组模型部署ID',
  `treatment_model_ref` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '创建时实验组模型标识快照',
  `treatment_endpoint_revision` int NOT NULL COMMENT '创建时实验组端点修订号',
  `dataset_release_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核通过的数据集命名版本ID',
  `dataset_version_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建时的数据集版本名快照',
  `dataset_snapshot_version_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '不可变数据集内容快照ID',
  `dataset_content_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '数据集内容SHA-256',
  `judge_deployment_id` bigint DEFAULT NULL COMMENT '创建时冻结的Judge部署ID',
  `judge_model_ref` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建时Judge模型标识快照',
  `judge_endpoint_revision` int DEFAULT NULL COMMENT '创建时Judge端点修订号',
  `offline_eval_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'NOT_STARTED/RUNNING/PASSED/FAILED',
  `offline_eval_started_at` datetime(6) DEFAULT NULL COMMENT '离线评测开始时间',
  `offline_eval_completed_at` datetime(6) DEFAULT NULL COMMENT '离线评测完成时间',
  `offline_eval_error` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '门禁失败摘要',
  `revision` int NOT NULL DEFAULT '1' COMMENT '不可变实验修订号',
  `assignment_salt` char(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '不可变确定性分桶盐值',
  `treatment_bps` int NOT NULL COMMENT '实验组流量，基点制1..9999',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/STOPPED/COMPLETED',
  `activation_task_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ACTIVATE可靠发布任务ID',
  `deactivation_task_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'DEACTIVATE可靠发布任务ID',
  `min_sample` bigint NOT NULL COMMENT '触发护栏判断的最小样本数',
  `max_error_rate` decimal(8,7) NOT NULL COMMENT '错误率护栏0..1',
  `max_p95_latency_ms` bigint NOT NULL COMMENT 'P95延迟护栏毫秒',
  `expires_at` datetime NOT NULL COMMENT '实验硬截止时间',
  `started_at` datetime DEFAULT NULL COMMENT '启动时间',
  `stopped_at` datetime DEFAULT NULL COMMENT '停止时间',
  `completed_at` datetime DEFAULT NULL COMMENT '正常到期完成时间',
  `stop_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '停止、自动停止或到期原因',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '修改人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `running_agent_id` bigint GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'RUNNING') then `agent_id` else NULL end)) STORED COMMENT '仅RUNNING态参与唯一约束',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_experiment_tenant_code` (`tenant_id`,`experiment_code`),
  UNIQUE KEY `uk_model_experiment_one_running_agent` (`tenant_id`,`running_agent_id`),
  KEY `idx_model_experiment_tenant_status` (`tenant_id`,`status`,`create_time` DESC),
  KEY `idx_model_experiment_expiry` (`status`,`expires_at`),
  KEY `idx_model_experiment_activation_task` (`tenant_id`,`activation_task_id`),
  KEY `idx_model_experiment_deactivation_task` (`tenant_id`,`deactivation_task_id`),
  CONSTRAINT `chk_model_experiment_distinct_arms` CHECK ((`control_deployment_id` <> `treatment_deployment_id`)),
  CONSTRAINT `chk_model_experiment_error_rate` CHECK ((`max_error_rate` between 0 and 1)),
  CONSTRAINT `chk_model_experiment_min_sample` CHECK ((`min_sample` >= 1)),
  CONSTRAINT `chk_model_experiment_p95` CHECK ((`max_p95_latency_ms` >= 1)),
  CONSTRAINT `chk_model_experiment_revision` CHECK ((`revision` >= 1)),
  CONSTRAINT `chk_model_experiment_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'RUNNING',_utf8mb4'STOPPED',_utf8mb4'COMPLETED'))),
  CONSTRAINT `chk_model_experiment_treatment_bps` CHECK ((`treatment_bps` between 1 and 9999))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='在线模型双臂实验定义';

-- ----------------------------------------------------------------------------
-- ai_model_experiment_arm_eval
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_experiment_arm_eval` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `experiment_id` bigint NOT NULL COMMENT '模型实验ID',
  `arm` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'CONTROL/TREATMENT',
  `attempt_no` int NOT NULL COMMENT '该臂评测尝试序号',
  `deployment_id` bigint NOT NULL COMMENT '被测部署ID',
  `endpoint_revision` int NOT NULL COMMENT '被测端点修订号',
  `dataset_release_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据集命名版本ID',
  `dataset_snapshot_version_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '不可变数据集快照ID',
  `dataset_content_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据集内容SHA-256',
  `judge_deployment_id` bigint NOT NULL COMMENT 'Judge部署ID',
  `judge_endpoint_revision` int NOT NULL COMMENT 'Judge端点修订号',
  `rubric_version` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评分rubric指纹',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'RUNNING/PASSED/FAILED/ERROR',
  `total` int DEFAULT NULL COMMENT '用例总数',
  `judged` int DEFAULT NULL COMMENT '成功评分数',
  `passed` int DEFAULT NULL COMMENT '通过用例数',
  `avg_score` decimal(8,6) DEFAULT NULL COMMENT '平均分1到5',
  `pass_rate` decimal(8,6) DEFAULT NULL COMMENT '通过率0到1',
  `failed_case_ids_json` text COLLATE utf8mb4_unicode_ci COMMENT '低分用例ID数组',
  `error_case_ids_json` text COLLATE utf8mb4_unicode_ci COMMENT '评分错误用例ID数组',
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '执行错误摘要',
  `started_at` datetime(6) NOT NULL COMMENT '开始时间',
  `completed_at` datetime(6) DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '写入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_experiment_arm_attempt` (`tenant_id`,`experiment_id`,`arm`,`attempt_no`),
  KEY `idx_model_experiment_arm_eval` (`tenant_id`,`experiment_id`,`attempt_no` DESC),
  CONSTRAINT `chk_model_experiment_arm` CHECK ((`arm` in (_utf8mb4'CONTROL',_utf8mb4'TREATMENT'))),
  CONSTRAINT `chk_model_experiment_arm_eval_status` CHECK ((`status` in (_utf8mb4'RUNNING',_utf8mb4'PASSED',_utf8mb4'FAILED',_utf8mb4'ERROR')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型实验control/treatment离线评测事实';

-- ----------------------------------------------------------------------------
-- ai_model_experiment_event
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_experiment_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `experiment_id` bigint NOT NULL COMMENT '实验ID',
  `event_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'START/STOP/AUTO_STOP/EXPIRED',
  `from_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '变更前状态',
  `to_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '变更后状态',
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '停止或系统判断原因',
  `actor_id` bigint DEFAULT NULL COMMENT '人工操作人；系统事件为空',
  `occurred_at` datetime NOT NULL COMMENT '事件发生时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_model_experiment_event_tenant_experiment` (`tenant_id`,`experiment_id`,`occurred_at` DESC),
  CONSTRAINT `chk_model_experiment_event_from_status` CHECK ((`from_status` in (_utf8mb4'DRAFT',_utf8mb4'RUNNING',_utf8mb4'STOPPED',_utf8mb4'COMPLETED'))),
  CONSTRAINT `chk_model_experiment_event_to_status` CHECK ((`to_status` in (_utf8mb4'DRAFT',_utf8mb4'RUNNING',_utf8mb4'STOPPED',_utf8mb4'COMPLETED'))),
  CONSTRAINT `chk_model_experiment_event_type` CHECK ((`event_type` in (_utf8mb4'START',_utf8mb4'STOP',_utf8mb4'AUTO_STOP',_utf8mb4'EXPIRED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='在线模型实验追加式生命周期事件';

-- ----------------------------------------------------------------------------
-- ai_model_health_event
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_health_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `model_config_id` bigint NOT NULL COMMENT '模型部署ID',
  `event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PROBE' COMMENT 'PROBE/STATE_TRANSITION/STALE_PROBE/OVERRIDE_*',
  `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MANUAL/SCHEDULED/RUNTIME/MIGRATION',
  `probe_kind` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CONNECTIVITY' COMMENT 'CONNECTIVITY/AUTH/CAPABILITY',
  `previous_health_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '事件前原始健康状态',
  `health_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '本次探测后的健康状态',
  `effective_health_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '覆盖后的有效健康状态',
  `override_mode` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'AUTO' COMMENT '事件对应覆盖模式',
  `operator_id` bigint DEFAULT NULL COMMENT '人工覆盖操作人',
  `operator_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '人工覆盖账号快照',
  `test_status` tinyint DEFAULT NULL COMMENT '探测事件为0/1/2，覆盖事件为空',
  `latency_ms` bigint DEFAULT NULL COMMENT '探测耗时',
  `error_category` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AUTH/RATE_LIMIT/TIMEOUT/CONTRACT/UNKNOWN',
  `message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '脱敏后的结果摘要',
  `occurred_at` datetime(6) NOT NULL COMMENT '探测实际开始时间（微秒）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_model_health_event_tenant_model` (`tenant_id`,`model_config_id`,`occurred_at` DESC),
  KEY `idx_model_health_event_category` (`tenant_id`,`error_category`,`occurred_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署健康事件';

-- ----------------------------------------------------------------------------
-- ai_model_health_snapshot
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_health_snapshot` (
  `model_config_id` bigint NOT NULL COMMENT '模型部署ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `health_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/HEALTHY/DEGRADED/UNHEALTHY/RECOVERING',
  `auth_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
  `capability_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
  `consecutive_failures` int NOT NULL DEFAULT '0' COMMENT '连续失败次数',
  `consecutive_successes` int NOT NULL DEFAULT '0' COMMENT '连续成功次数',
  `last_latency_ms` bigint DEFAULT NULL COMMENT '最近探测耗时',
  `last_error_category` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AUTH/RATE_LIMIT/TIMEOUT/CONTRACT/UNKNOWN',
  `last_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '脱敏后的最近结果摘要',
  `last_probe_at` datetime(6) DEFAULT NULL COMMENT '最近探测实际开始时间（微秒）',
  `last_success_at` datetime DEFAULT NULL COMMENT '最近成功时间',
  `last_failure_at` datetime DEFAULT NULL COMMENT '最近失败时间',
  `next_probe_at` datetime DEFAULT NULL COMMENT '下次计划探测时间',
  `cooldown_until` datetime(6) DEFAULT NULL COMMENT 'UNHEALTHY 冷却截止时间',
  `override_mode` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/FORCE_HEALTHY/FORCE_UNHEALTHY',
  `override_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '人工覆盖理由',
  `override_operator_id` bigint DEFAULT NULL COMMENT '覆盖操作人',
  `override_operator_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '覆盖操作人账号快照',
  `override_until` datetime(6) DEFAULT NULL COMMENT '人工覆盖到期时间',
  `revision` int NOT NULL DEFAULT '1' COMMENT '快照修订号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`model_config_id`),
  KEY `idx_model_health_tenant_status` (`tenant_id`,`health_status`,`next_probe_at`),
  KEY `idx_model_health_override_expiry` (`override_mode`,`override_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署健康快照';

-- ----------------------------------------------------------------------------
-- ai_model_price
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_price` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型厂商，如 dashscope/openai',
  `model_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名，如 qwen-max',
  `input_price` decimal(16,6) NOT NULL DEFAULT '0.000000' COMMENT '输入单价（元/百万 token）',
  `output_price` decimal(16,6) NOT NULL DEFAULT '0.000000' COMMENT '输出单价（元/百万 token）',
  `cached_price` decimal(16,6) NOT NULL DEFAULT '0.000000' COMMENT '缓存命中输入单价（元/百万 token）',
  `currency` varchar(8) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `effective_from` datetime NOT NULL COMMENT '生效时间；调价不改旧行而是插新行，历史账单才算得回去',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_model_price_lookup` (`provider`,`model_name`,`effective_from`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型单价（按生效时间留历史，供账单回溯）';

-- ----------------------------------------------------------------------------
-- ai_model_route_policy
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_route_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `policy_code` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户内稳定策略编码',
  `policy_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '策略说明',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/DISABLED',
  `current_version_id` bigint DEFAULT NULL COMMENT '当前生效的不可变版本ID',
  `current_version_no` int DEFAULT NULL COMMENT '当前生效版本号',
  `latest_version_no` int NOT NULL DEFAULT '0' COMMENT '已创建的最新版本号',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '修改人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_route_policy_tenant_code` (`tenant_id`,`policy_code`,`deleted`),
  KEY `idx_model_route_policy_tenant_status` (`tenant_id`,`status`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型路由策略身份';

-- ----------------------------------------------------------------------------
-- ai_model_route_policy_version
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_route_policy_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `policy_id` bigint NOT NULL COMMENT '路由策略ID',
  `version_no` int NOT NULL COMMENT '租户策略内单调递增版本号',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/RETIRED',
  `content_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则规范化内容 SHA-256',
  `change_note` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '版本变更说明',
  `activated_by` bigint DEFAULT NULL COMMENT '激活人',
  `activated_at` datetime DEFAULT NULL COMMENT '激活时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_route_policy_version` (`tenant_id`,`policy_id`,`version_no`),
  KEY `idx_model_route_version_policy_status` (`tenant_id`,`policy_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型路由策略不可变版本';

-- ----------------------------------------------------------------------------
-- ai_model_route_rule
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_model_route_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `policy_version_id` bigint NOT NULL COMMENT '不可变策略版本ID',
  `purpose` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'DEFAULT/ECONOMY/COMPLEX_REASONING/FALLBACK',
  `deployment_id` bigint NOT NULL COMMENT 'ai_model_config.id，仅引用部署，不复制凭据',
  `priority` int NOT NULL COMMENT '数值越小优先级越高',
  `condition_json` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '类型化条件 JSON；空对象表示无条件',
  `condition_summary` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供审计和命中解释的条件摘要',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_model_route_rule_version_priority` (`tenant_id`,`policy_version_id`,`priority`),
  KEY `idx_model_route_rule_deployment` (`tenant_id`,`deployment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型路由版本规则';

-- ----------------------------------------------------------------------------
-- ai_plan_confirmation
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_plan_confirmation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `agent_code` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '智能体编码',
  `session_id` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID',
  `plan_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '计划ID',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/TIMEOUT/CANCELLED',
  `expire_at` datetime NOT NULL COMMENT '确认截止时间',
  `resolved_at` datetime DEFAULT NULL COMMENT '终态时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_confirmation_scope` (`tenant_id`,`agent_code`,`session_id`,`plan_id`),
  KEY `idx_plan_confirmation_pending` (`tenant_id`,`status`,`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Plan/HITL 跨 Pod 挂起态';

-- ----------------------------------------------------------------------------
-- ai_project
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_project` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '项目名称',
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_ai_project_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话项目分组（跨智能体自由归类会话）';

-- ----------------------------------------------------------------------------
-- ai_project_session
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_project_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话所属智能体编码',
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID（AgentStateStore 里的逻辑 key，非本表自增主键）',
  `create_by` bigint DEFAULT NULL COMMENT '添加人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_project_session` (`project_id`,`agent_code`,`session_id`),
  KEY `idx_ai_project_session_project` (`project_id`),
  KEY `idx_ai_project_session_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目-会话关联';

-- ----------------------------------------------------------------------------
-- ai_runtime_config_ack
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_runtime_config_ack` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `revision` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `instance_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `applied_at_ms` bigint NOT NULL,
  `created_at_ms` bigint NOT NULL,
  `updated_at_ms` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_runtime_ack_tenant_revision_instance` (`tenant_id`,`revision`,`instance_id`),
  KEY `idx_runtime_ack_revision_status` (`tenant_id`,`revision`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行时配置实例应用回执';

-- ----------------------------------------------------------------------------
-- ai_runtime_publish_task
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_runtime_publish_task` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `seq` bigint NOT NULL AUTO_INCREMENT COMMENT '严格写入顺序，避免同毫秒任务排序不确定',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_id` bigint NOT NULL,
  `experiment_id` bigint DEFAULT NULL COMMENT '在线实验ID；通用发布任务为空',
  `experiment_publish_action` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ACTIVATE/DEACTIVATE；通用发布任务为空',
  `operation_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '一次回滚/灰度操作ID；灰度多任务共用',
  `publish_intent` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/SAFE_ROLLBACK/SAFE_GRAY',
  `source_config_version_id` bigint DEFAULT NULL COMMENT '白名单补丁来源配置版本主键',
  `source_content_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源完整快照SHA-256，仅作完整性审计',
  `rollback_patch_json` json DEFAULT NULL COMMENT '仅允许systemPrompt/maxIters，不含模型、凭据、MCP、路由与实验',
  `channel_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `data_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `revision` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ack_targets_json` text COLLATE utf8mb4_unicode_ci COMMENT '入队时冻结的目标实例ID JSON',
  `publish_scope` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'FULL',
  `gray_tenants` text COLLATE utf8mb4_unicode_ci,
  `source_version` int DEFAULT NULL,
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at_ms` bigint NOT NULL,
  `lease_owner` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until_ms` bigint NOT NULL DEFAULT '0',
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `candidate_versions_json` text COLLATE utf8mb4_unicode_ci COMMENT '待发布候选的可比版本绑定JSON',
  `gate_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'NOT_REQUIRED/PENDING/PASSED/BLOCKED/OVERRIDDEN',
  `gate_eval_run_ids_json` text COLLATE utf8mb4_unicode_ci COMMENT '本次判定使用的EvalRun ID数组',
  `gate_decision_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '完整门禁判定JSON',
  `gate_evaluated_at_ms` bigint DEFAULT NULL COMMENT '门禁判定时间戳',
  `gate_override_id` bigint DEFAULT NULL COMMENT '紧急豁免审计ID',
  `created_at_ms` bigint NOT NULL,
  `updated_at_ms` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_runtime_publish_seq` (`seq`),
  UNIQUE KEY `uk_runtime_publish_tenant_revision` (`tenant_id`,`revision`),
  KEY `idx_runtime_publish_due` (`status`,`next_attempt_at_ms`,`lease_until_ms`),
  KEY `idx_runtime_publish_target` (`tenant_id`,`target_id`,`seq`),
  KEY `idx_runtime_publish_gate` (`tenant_id`,`gate_status`,`seq`),
  KEY `idx_runtime_publish_experiment` (`tenant_id`,`experiment_id`,`seq`),
  KEY `idx_runtime_publish_operation` (`tenant_id`,`operation_id`,`seq`),
  KEY `idx_runtime_publish_source_version` (`tenant_id`,`source_config_version_id`,`seq`),
  KEY `idx_runtime_publish_nacos_key` (`tenant_id`,`data_id`,`group_name`,`seq`,`status`),
  CONSTRAINT `chk_runtime_publish_experiment_intent` CHECK ((((`experiment_id` is null) and (`experiment_publish_action` is null)) or ((`experiment_id` is not null) and (`experiment_publish_action` in (_utf8mb4'ACTIVATE',_utf8mb4'DEACTIVATE'))))),
  CONSTRAINT `chk_runtime_publish_safe_intent` CHECK ((((`publish_intent` = _utf8mb4'NORMAL') and (`source_config_version_id` is null) and (`source_content_hash` is null) and (`rollback_patch_json` is null)) or ((`publish_intent` in (_utf8mb4'SAFE_ROLLBACK',_utf8mb4'SAFE_GRAY')) and (`operation_id` is not null) and (`source_config_version_id` is not null) and (`source_content_hash` is not null) and (`rollback_patch_json` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行时配置可靠发布任务';

-- ----------------------------------------------------------------------------
-- ai_scheduled_task
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_scheduled_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务编码（= XXL-JOB JobHandler 调用时的业务标识，全局唯一）',
  `task_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务名称',
  `agent_id` bigint NOT NULL COMMENT '关联智能体ID（逻辑关联 ai_agent.id）',
  `prompt` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '每次触发时传给 Agent 的用户消息内容',
  `cron` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'cron 表达式（内置动态调度器按此周期执行；为空则不参与内置周期调度，仅可手动/外部触发）',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否允许执行：0禁用 / 1启用',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_scheduled_task_code` (`task_code`),
  KEY `idx_ai_scheduled_task_agent` (`agent_id`),
  KEY `idx_ai_scheduled_task_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务定义';

-- ----------------------------------------------------------------------------
-- ai_scheduled_task_claim
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_scheduled_task_claim` (
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '任务所属租户',
  `task_id` bigint NOT NULL COMMENT '定时任务ID',
  `task_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务编码快照',
  `fire_time` datetime(3) NOT NULL COMMENT 'Cron 计算出的计划触发时刻',
  `owner_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '成功认领的 Admin 实例ID',
  `claim_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '认领时间',
  PRIMARY KEY (`tenant_id`,`task_id`,`fire_time`),
  KEY `idx_scheduled_task_claim_time` (`claim_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内置定时任务多Pod触发认领';

-- ----------------------------------------------------------------------------
-- ai_scheduled_task_run
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_scheduled_task_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL COMMENT '任务ID',
  `task_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务编码（冗余，任务被删除后历史记录仍可读）',
  `trigger_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发方式：XXL_JOB / MANUAL',
  `start_time` datetime NOT NULL COMMENT '开始执行时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `cost_ms` bigint DEFAULT NULL COMMENT '耗时（毫秒）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '执行结果：SUCCESS / FAILED',
  `output` text COLLATE utf8mb4_unicode_ci COMMENT 'Agent 回复内容（落库前截断到 8000 字符）',
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_scheduled_task_run_task_time` (`task_id`,`start_time` DESC),
  KEY `idx_ai_scheduled_task_run_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务执行历史（只追加，不做逻辑删除）';

-- ----------------------------------------------------------------------------
-- ai_secret_material
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_secret_material` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `secret_ref_id` bigint NOT NULL COMMENT 'ai_secret_ref.id',
  `version` int NOT NULL COMMENT '不可变密钥版本',
  `cipher_text` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'LOCAL_AES 密文，禁止通过接口返回',
  `key_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'admin-aes-gcm' COMMENT '加密主密钥标识',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUPERSEDED/REVOKED',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_secret_material_version` (`secret_ref_id`,`version`),
  KEY `idx_secret_material_tenant_ref` (`tenant_id`,`secret_ref_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地加密密钥版本';

-- ----------------------------------------------------------------------------
-- ai_secret_ref
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_secret_ref` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `ref_code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户内稳定凭据引用编码',
  `ref_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '凭据名称',
  `provider_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LOCAL_AES' COMMENT 'LOCAL_AES/VAULT/AWS_SM/AZURE_KV/GCP_SM/ENV',
  `external_ref` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '外部密钥管理器引用，不保存密钥值',
  `current_version` int NOT NULL DEFAULT '1' COMMENT '当前版本',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/DISABLED/ERROR',
  `expires_at` datetime DEFAULT NULL COMMENT '凭据过期时间',
  `last_rotated_at` datetime DEFAULT NULL COMMENT '最近轮换时间',
  `last_rotated_by` bigint DEFAULT NULL COMMENT '最近轮换人',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '修改人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_secret_ref_tenant_code` (`tenant_id`,`ref_code`,`deleted`),
  KEY `idx_secret_ref_tenant_status` (`tenant_id`,`status`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='密钥引用元数据';

-- ----------------------------------------------------------------------------
-- ai_site_message
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_site_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '接收人（admin 用户 id）',
  `title` varchar(200) NOT NULL COMMENT '消息标题',
  `content` varchar(2000) DEFAULT NULL COMMENT '消息正文',
  `biz_type` varchar(50) NOT NULL COMMENT '业务类型（如 CODE_REVIEW）',
  `biz_id` varchar(64) DEFAULT NULL COMMENT '业务主键',
  `link` varchar(500) DEFAULT NULL COMMENT '前端跳转路由（可空）',
  `read_flag` tinyint NOT NULL DEFAULT '0' COMMENT '已读标记：0未读/1已读',
  `read_time` datetime DEFAULT NULL COMMENT '标记已读时间',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`read_flag`),
  KEY `idx_ai_site_message_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用站内消息';

-- ----------------------------------------------------------------------------
-- ai_skill
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `skill_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '技能名称',
  `skill_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '技能编码',
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '技能内容/定义（SKILL.md 内容）',
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 / 1启用',
  `current_version_id` bigint DEFAULT NULL COMMENT '当前不可变版本ID',
  `latest_version_no` int NOT NULL DEFAULT '0' COMMENT '最新版本号',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `storage_targets` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'local' COMMENT '存储目标，逗号分隔：local,nacos,sftp',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_code` (`skill_code`),
  KEY `idx_ai_skill_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 配置';

-- ----------------------------------------------------------------------------
-- ai_skill_file
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_skill_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `skill_id` bigint NOT NULL COMMENT '所属 Skill（ai_skill.id）',
  `file_path` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相对 SKILL.md 所在目录的路径，如 references/api.md',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `content` longblob COMMENT '文件内容（文本/二进制统一按字节存）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_ai_skill_file_skill` (`skill_id`),
  KEY `idx_ai_skill_file_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 附属文件（zip 上传的 references/scripts 等）';

-- ----------------------------------------------------------------------------
-- ai_skill_version
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_skill_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `skill_id` bigint NOT NULL COMMENT '稳定 Skill ID',
  `version_no` int NOT NULL COMMENT '版本号',
  `skill_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本冻结名称',
  `skill_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本冻结编码',
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本冻结 SKILL.md',
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '版本冻结描述',
  `content_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '版本内容指纹',
  `change_note` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '变更说明',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_version_no` (`tenant_id`,`skill_id`,`version_no`),
  KEY `idx_ai_skill_version_skill` (`tenant_id`,`skill_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 不可变版本';

-- ----------------------------------------------------------------------------
-- ai_skill_version_file
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_skill_version_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID',
  `skill_version_id` bigint NOT NULL COMMENT 'Skill 版本ID',
  `file_path` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相对文件路径',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `content` longblob COMMENT '文件内容',
  `content_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件内容指纹',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_version_file` (`skill_version_id`,`file_path`),
  KEY `idx_ai_skill_version_file_tenant` (`tenant_id`,`skill_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 版本附属文件';

-- ----------------------------------------------------------------------------
-- ai_slo_alert
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_slo_alert` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `policy_id` bigint NOT NULL COMMENT 'SLO策略ID',
  `window_end_minute` bigint NOT NULL COMMENT '评估时刻UTC分钟桶',
  `alert_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MULTI_WINDOW_BURN',
  `active_policy_id` bigint DEFAULT NULL COMMENT '活跃告警策略ID，恢复后置空',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKED/RESOLVED',
  `short_burn_rate` decimal(12,6) NOT NULL COMMENT '短窗口燃烧率',
  `long_burn_rate` decimal(12,6) NOT NULL COMMENT '长窗口燃烧率',
  `first_seen_at` datetime NOT NULL COMMENT '首次发现时间',
  `last_seen_at` datetime DEFAULT NULL COMMENT '最近燃烧或恢复观测时间',
  `ack_by` bigint DEFAULT NULL COMMENT '确认人',
  `ack_at` datetime DEFAULT NULL COMMENT '确认时间',
  `resolved_at` datetime DEFAULT NULL COMMENT '恢复时间',
  `update_time` datetime DEFAULT NULL COMMENT '状态更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slo_alert_active` (`tenant_id`,`active_policy_id`),
  KEY `idx_slo_alert_tenant_time` (`tenant_id`,`first_seen_at` DESC),
  KEY `idx_slo_alert_tenant_status` (`tenant_id`,`status`,`last_seen_at`),
  CONSTRAINT `chk_slo_alert_type` CHECK ((`alert_type` = _utf8mb4'MULTI_WINDOW_BURN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO错误预算告警事实';

-- ----------------------------------------------------------------------------
-- ai_slo_alert_event
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_slo_alert_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `alert_id` bigint NOT NULL,
  `policy_id` bigint NOT NULL,
  `event_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'OPENED/ACKED/RESOLVED',
  `actor_user_id` bigint DEFAULT NULL,
  `short_burn_rate` decimal(12,6) NOT NULL,
  `long_burn_rate` decimal(12,6) NOT NULL,
  `occurred_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slo_alert_event_once` (`alert_id`,`event_type`),
  KEY `idx_slo_alert_event_tenant_time` (`tenant_id`,`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO告警状态迁移事件';

-- ----------------------------------------------------------------------------
-- ai_slo_notification_task
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_slo_notification_task` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_id` bigint NOT NULL,
  `alert_id` bigint NOT NULL,
  `policy_id` bigint NOT NULL,
  `event_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PENDING/PROCESSING/DELIVERED',
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at_ms` bigint NOT NULL,
  `lease_owner` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until_ms` bigint NOT NULL DEFAULT '0',
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `recipient_count` int NOT NULL DEFAULT '0',
  `created_at_ms` bigint NOT NULL,
  `updated_at_ms` bigint NOT NULL,
  `delivered_at_ms` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slo_notification_event` (`event_id`),
  KEY `idx_slo_notification_due` (`status`,`next_attempt_at_ms`,`lease_until_ms`),
  KEY `idx_slo_notification_tenant` (`tenant_id`,`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO告警可靠通知任务';

-- ----------------------------------------------------------------------------
-- ai_slo_policy
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_slo_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `policy_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `scope_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'TENANT/AGENT/CHANNEL',
  `scope_key` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Agent编码或渠道编码；租户范围为空',
  `availability_target` decimal(8,7) NOT NULL COMMENT '成功率目标0..1',
  `latency_target` decimal(8,7) NOT NULL COMMENT '阈值内完成比例目标0..1',
  `latency_threshold_ms` bigint NOT NULL COMMENT '延迟阈值毫秒',
  `short_window_minutes` int NOT NULL COMMENT '短窗口分钟',
  `long_window_minutes` int NOT NULL COMMENT '长窗口分钟',
  `minimum_sample_count` int NOT NULL DEFAULT '100' COMMENT '短长窗口各自触发评估所需的最低调用样本数',
  `burn_rate_threshold` decimal(12,6) NOT NULL COMMENT '短长窗口共同触发的燃烧率阈值',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `next_evaluation_at_ms` bigint NOT NULL DEFAULT '0' COMMENT '下次周期评估时间',
  `evaluation_lease_owner` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '周期评估租约持有者',
  `evaluation_lease_until_ms` bigint NOT NULL DEFAULT '0' COMMENT '周期评估租约截止时间',
  `evaluation_failures` int NOT NULL DEFAULT '0' COMMENT '连续评估失败次数',
  `last_evaluated_at` datetime DEFAULT NULL COMMENT '最近评估时间',
  `last_evaluation_status` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近评估状态',
  `last_evaluation_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近评估错误',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slo_policy_tenant_name` (`tenant_id`,`policy_name`),
  KEY `idx_slo_policy_tenant_scope` (`tenant_id`,`scope_type`,`scope_key`,`enabled`),
  KEY `idx_slo_policy_evaluation_due` (`enabled`,`next_evaluation_at_ms`,`evaluation_lease_until_ms`),
  CONSTRAINT `chk_slo_policy_availability` CHECK (((`availability_target` > 0) and (`availability_target` < 1))),
  CONSTRAINT `chk_slo_policy_burn` CHECK ((`burn_rate_threshold` > 0)),
  CONSTRAINT `chk_slo_policy_latency` CHECK (((`latency_target` > 0) and (`latency_target` < 1))),
  CONSTRAINT `chk_slo_policy_latency_ms` CHECK ((`latency_threshold_ms` > 0)),
  CONSTRAINT `chk_slo_policy_minimum_samples` CHECK ((`minimum_sample_count` > 0)),
  CONSTRAINT `chk_slo_policy_scope` CHECK ((`scope_type` in (_utf8mb4'TENANT',_utf8mb4'AGENT',_utf8mb4'CHANNEL'))),
  CONSTRAINT `chk_slo_policy_windows` CHECK (((`short_window_minutes` > 0) and (`long_window_minutes` > `short_window_minutes`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户SLO策略';

-- ----------------------------------------------------------------------------
-- ai_system_tool
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_system_tool` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tool_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具编码（唯一，运行时按此值取同名 Spring Bean，不可修改）',
  `tool_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具展示名称',
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具描述',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：0禁用 / 1启用',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_system_tool_code` (`tool_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统工具目录（代码定义，库存启停状态）';

-- ----------------------------------------------------------------------------
-- ai_workspace_session
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_workspace_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_user_id` bigint NOT NULL,
  `created_at_ms` bigint NOT NULL,
  `updated_at_ms` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_session_tenant_agent_session` (`tenant_id`,`agent_code`,`session_id`),
  KEY `idx_workspace_session_tenant_owner` (`tenant_id`,`owner_user_id`,`updated_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作区会话租户与用户归属';

-- ----------------------------------------------------------------------------
-- cw_agent_call_log
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_agent_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `request_id` varchar(64) NOT NULL DEFAULT '' COMMENT '请求ID（全链路关联）',
  `user_id` varchar(128) NOT NULL DEFAULT '' COMMENT '用户ID（ctx.userId）',
  `username` varchar(128) NOT NULL DEFAULT '' COMMENT '用户名',
  `agent_code` varchar(128) NOT NULL DEFAULT '' COMMENT '智能体编码',
  `agent_name` varchar(255) NOT NULL DEFAULT '' COMMENT '智能体名称',
  `session_id` varchar(128) NOT NULL DEFAULT '' COMMENT '会话ID',
  `session_type` varchar(32) NOT NULL DEFAULT 'CHAT' COMMENT '会话类型 CHAT/VIBE_CODING',
  `question` mediumtext COMMENT '用户问题',
  `answer` mediumtext COMMENT '智能体回答',
  `start_time` bigint NOT NULL COMMENT '调用开始时间戳（毫秒）',
  `end_time` bigint NOT NULL COMMENT '调用结束时间戳（毫秒）',
  `duration_ms` bigint NOT NULL DEFAULT '0' COMMENT '总耗时（毫秒）',
  `model_ms` bigint NOT NULL DEFAULT '0' COMMENT 'MODEL段耗时合计（毫秒）',
  `tool_ms` bigint NOT NULL DEFAULT '0' COMMENT 'TOOL段耗时合计（毫秒）',
  `mcp_ms` bigint NOT NULL DEFAULT '0' COMMENT 'MCP段耗时合计（毫秒）',
  `skill_ms` bigint NOT NULL DEFAULT '0' COMMENT 'SKILL段耗时合计（毫秒）',
  `segment_count` int NOT NULL DEFAULT '0' COMMENT '分段总数',
  `input_tokens` bigint DEFAULT NULL COMMENT '请求级输入token合计（缺失为NULL）',
  `output_tokens` bigint DEFAULT NULL COMMENT '请求级输出token合计（缺失为NULL）',
  `total_tokens` bigint DEFAULT NULL COMMENT '请求级总token合计（缺失为NULL）',
  `cached_tokens` bigint DEFAULT NULL COMMENT '命中缓存的输入token（input_tokens的子集，不计入total）',
  `model_reported_ms` bigint DEFAULT NULL COMMENT '模型自报耗时合计（毫秒），与model_ms之差=网络/排队开销',
  `model_cost_amount` decimal(30,14) DEFAULT NULL COMMENT '本次调用已结算模型金额',
  `model_cost_currency` varchar(16) DEFAULT NULL COMMENT '单币种结算币种',
  `model_cost_status` varchar(24) NOT NULL DEFAULT 'NO_MODEL' COMMENT 'COMPLETE/PARTIAL/UNAVAILABLE/MULTI_CURRENCY/NO_MODEL',
  `model_segment_count` int NOT NULL DEFAULT '0' COMMENT '模型分段数',
  `settled_cost_segment_count` int NOT NULL DEFAULT '0' COMMENT '已结算模型分段数',
  `unsettled_cost_segment_count` int NOT NULL DEFAULT '0' COMMENT '未结算模型分段数',
  `trace_id` varchar(32) DEFAULT NULL COMMENT 'W3C trace-id，关联 OTel/Tempo',
  `runtime_revision` varchar(64) DEFAULT NULL COMMENT '实例实际应用的运行配置发布修订',
  `runtime_content_hash` char(64) DEFAULT NULL COMMENT '运行配置内容摘要，关联发布任务与实例ACK',
  `version_binding_json` json DEFAULT NULL COMMENT '模型/提示词/Agent/知识库/工具版本绑定（不含密钥）',
  `replay_snapshot_json` json DEFAULT NULL COMMENT '脱敏模型参数、RAG与工具重放事实',
  `experiment_id` bigint DEFAULT NULL COMMENT '在线实验ID',
  `experiment_revision` int DEFAULT NULL COMMENT '不可变实验修订号',
  `experiment_arm` varchar(16) DEFAULT NULL COMMENT 'CONTROL/TREATMENT',
  `experiment_deployment_id` bigint DEFAULT NULL COMMENT '实际命中的模型部署ID',
  `experiment_bucket` int DEFAULT NULL COMMENT '稳定分桶0..9999；无可信主体时为空',
  `success` tinyint(1) NOT NULL DEFAULT '1' COMMENT '整次调用是否成功',
  `error_msg` varchar(1024) DEFAULT NULL COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_call_request` (`request_id`),
  KEY `idx_call_username` (`username`),
  KEY `idx_call_agent_code` (`agent_code`),
  KEY `idx_call_session` (`session_id`),
  KEY `idx_call_start` (`start_time`),
  KEY `idx_cw_agent_call_log_tenant` (`tenant_id`),
  KEY `idx_call_trace_id` (`trace_id`),
  KEY `idx_call_runtime_revision` (`runtime_revision`),
  KEY `idx_call_experiment_arm` (`experiment_id`,`experiment_revision`,`experiment_arm`,`start_time`),
  KEY `idx_agent_call_cost_window` (`tenant_id`,`start_time`,`model_cost_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体调用主记录（分段耗时统计）';

-- ----------------------------------------------------------------------------
-- cw_agent_call_segment
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_agent_call_segment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `call_log_id` bigint NOT NULL COMMENT '所属主记录ID',
  `seq` int NOT NULL COMMENT '调用内分段序号（从1起）',
  `kind` varchar(16) NOT NULL COMMENT '分段类别 MODEL/TOOL/MCP/SKILL',
  `name` varchar(255) NOT NULL DEFAULT '' COMMENT '分段名称（模型名/工具名）',
  `start_time` bigint NOT NULL COMMENT '分段开始时间戳（毫秒）',
  `duration_ms` bigint NOT NULL DEFAULT '0' COMMENT '分段耗时（毫秒）',
  `input_tokens` bigint DEFAULT NULL COMMENT '输入token（仅MODEL段，缺失为NULL）',
  `output_tokens` bigint DEFAULT NULL COMMENT '输出token（仅MODEL段，缺失为NULL）',
  `cached_tokens` bigint DEFAULT NULL COMMENT '命中缓存的输入token（仅MODEL段）',
  `model_reported_ms` bigint DEFAULT NULL COMMENT '模型自报耗时（毫秒，仅MODEL段）',
  `provider` varchar(64) DEFAULT NULL COMMENT '实际模型供应商',
  `deployment_id` bigint DEFAULT NULL COMMENT '实际模型部署ID',
  `model_name` varchar(191) DEFAULT NULL COMMENT '实际模型名',
  `price_id` bigint DEFAULT NULL COMMENT '调用时冻结的价目ID',
  `currency` varchar(16) DEFAULT NULL COMMENT '调用时冻结的币种',
  `input_unit_price` decimal(20,8) DEFAULT NULL COMMENT '调用时输入单价（每百万token）',
  `output_unit_price` decimal(20,8) DEFAULT NULL COMMENT '调用时输出单价（每百万token）',
  `cached_unit_price` decimal(20,8) DEFAULT NULL COMMENT '调用时缓存输入单价（每百万token）',
  `pricing_status` varchar(16) NOT NULL DEFAULT 'UNPRICED' COMMENT 'PRICED/UNPRICED',
  `cost_amount` decimal(30,14) DEFAULT NULL COMMENT '按冻结价目结算的模型金额',
  `cost_currency` varchar(16) DEFAULT NULL COMMENT '结算币种',
  `cost_status` varchar(24) NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT 'SETTLED/UNPRICED/USAGE_MISSING/USAGE_INVALID/NOT_APPLICABLE',
  `success` tinyint(1) NOT NULL DEFAULT '1' COMMENT '分段是否成功',
  `error_msg` varchar(1024) DEFAULT NULL COMMENT '失败原因',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_segment_call_log` (`call_log_id`,`seq`),
  KEY `idx_cw_agent_call_segment_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体调用分段明细';

-- ----------------------------------------------------------------------------
-- cw_tenant_usage_daily
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_tenant_usage_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户ID',
  `stat_date` date NOT NULL COMMENT '统计日期（自然日）',
  `provider` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '模型厂商',
  `model_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '模型名',
  `call_count` bigint NOT NULL DEFAULT '0' COMMENT '调用次数',
  `input_tokens` bigint NOT NULL DEFAULT '0' COMMENT '输入 token',
  `output_tokens` bigint NOT NULL DEFAULT '0' COMMENT '输出 token',
  `cached_tokens` bigint NOT NULL DEFAULT '0' COMMENT '缓存命中输入 token（input 的子集）',
  `total_tokens` bigint NOT NULL DEFAULT '0' COMMENT '总 token',
  `model_segment_count` bigint NOT NULL DEFAULT '0' COMMENT '模型分段数',
  `settled_segment_count` bigint NOT NULL DEFAULT '0' COMMENT '已结算模型分段数',
  `unsettled_segment_count` bigint NOT NULL DEFAULT '0' COMMENT '未结算模型分段数',
  `amount` decimal(30,14) NOT NULL DEFAULT '0.00000000000000' COMMENT '已结算模型金额（由调用事实精确求和）',
  `currency` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '币种；不同币种禁止相加',
  `source_max_call_log_id` bigint NOT NULL DEFAULT '0' COMMENT '本次归集冻结的客服端调用日志上界',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_usage_daily` (`tenant_id`,`stat_date`,`provider`,`model_name`,`currency`),
  KEY `idx_usage_daily_date` (`stat_date`),
  KEY `idx_usage_daily_tenant` (`tenant_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户日用量归集（账单与报表数据源）';

-- ----------------------------------------------------------------------------
-- cw_usage_aggregation_lock
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_usage_aggregation_lock` (
  `stat_date` date NOT NULL COMMENT '归集日期',
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后一次领取时间',
  PRIMARY KEY (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日账单归集数据库串行锁';

-- ----------------------------------------------------------------------------
-- login_carousel_image
-- ----------------------------------------------------------------------------
CREATE TABLE `login_carousel_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `image_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上传时的原始文件名（管理页展示用）',
  `image_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对外访问相对URL：/api/login-images/{uuid}.{ext}',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '轮播顺序，小的在前',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：0禁用 / 1启用',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_login_carousel_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录页轮播背景图';

-- ----------------------------------------------------------------------------
-- sql_datasource
-- ----------------------------------------------------------------------------
CREATE TABLE `sql_datasource` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据源名称（唯一，供 SQL 定义下拉选择）',
  `jdbc_url` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'JDBC 连接串',
  `username` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接用户名',
  `password` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接密码（AES-GCM 密文，永不明文返回）',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：0禁用 / 1启用',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sql_datasource_name` (`name`),
  KEY `idx_sql_datasource_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 配置外部数据源';

-- ----------------------------------------------------------------------------
-- sql_define
-- ----------------------------------------------------------------------------
CREATE TABLE `sql_define` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `define_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '定义编码（唯一，通用查询接口按此 key 执行）',
  `datasource_id` bigint NOT NULL COMMENT '关联数据源ID（逻辑关联 sql_datasource.id）',
  `sql_describe` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SQL 用途描述',
  `query_sql` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '查询 SQL（命名参数 :paramName，强制只读 SELECT/WITH）',
  `count_sql` text COLLATE utf8mb4_unicode_ci COMMENT '总数 SQL（可空；为空则前端只有上/下一页无总数）',
  `auto_load` tinyint NOT NULL DEFAULT '0' COMMENT '打开页面是否自动执行：0否 / 1是',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：0禁用 / 1启用',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sql_define_key` (`define_key`),
  KEY `idx_sql_define_datasource` (`datasource_id`),
  KEY `idx_sql_define_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 查询定义';

-- ----------------------------------------------------------------------------
-- sql_define_param
-- ----------------------------------------------------------------------------
CREATE TABLE `sql_define_param` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `define_id` bigint NOT NULL COMMENT '所属 SQL 定义ID',
  `param_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数名（对应 SQL 里的 :paramName）',
  `param_desc` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '参数说明（前端表单 label）',
  `param_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数类型：STRING / INTEGER / DATETIME',
  `date_format` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '日期格式（仅 DATETIME 类型生效，如 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd；空默认 yyyy-MM-dd HH:mm:ss）',
  `required` tinyint NOT NULL DEFAULT '0' COMMENT '是否必填：0否 / 1是',
  `default_value` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '默认值（支持 ${now}/${now-14d}/${now-2h} 时间表达式）',
  `drop_down` text COLLATE utf8mb4_unicode_ci COMMENT '下拉选项（JSON 对象 {"值":"显示名"}，可空）',
  `is_page_num` tinyint NOT NULL DEFAULT '0' COMMENT '是否分页页码参数：其值换算为 offset 绑定',
  `is_page_size` tinyint NOT NULL DEFAULT '0' COMMENT '是否分页页大小参数：绑定 pageSize 本身',
  `sort` int NOT NULL DEFAULT '0' COMMENT '表单排序（升序）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_sql_define_param_define` (`define_id`),
  KEY `idx_sql_define_param_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 查询参数元数据';

-- ----------------------------------------------------------------------------
-- sql_field_transform
-- ----------------------------------------------------------------------------
CREATE TABLE `sql_field_transform` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `define_id` bigint NOT NULL COMMENT '所属 SQL 定义ID',
  `field_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '匹配的结果列名',
  `transform_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '转换类型：DATE_FORMAT / VALUE_MAP',
  `transform_config` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '转换配置（DATE_FORMAT 为格式串 / VALUE_MAP 为 JSON 映射）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  KEY `idx_sql_field_transform_define` (`define_id`),
  KEY `idx_sql_field_transform_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 结果列转换器';

-- ----------------------------------------------------------------------------
-- sys_menu_change_log
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_menu_change_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `menu_id` bigint NOT NULL COMMENT '被操作的菜单节点ID（删除后节点已不存在，此处仍保留历史指向）',
  `action` varchar(16) NOT NULL COMMENT '操作类型：CREATE/UPDATE/DELETE/MOVE',
  `before_snapshot` text COMMENT '变更前节点 JSON（CREATE 时为空）',
  `after_snapshot` text COMMENT '变更后节点 JSON（DELETE 时为空）',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人用户ID',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作人昵称（冗余存储，用户改名/删除后历史记录仍可读）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_menu_change_log_menu` (`menu_id`),
  KEY `idx_menu_change_log_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单变更审计流水（排查用，不支持回滚）';

-- ----------------------------------------------------------------------------
-- sys_operation_log
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '操作人ID（登录失败等未认证场景可为空）',
  `username` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人账号',
  `operation` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型（登录/登出/新增/修改/删除/测试等）',
  `method` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求方法/接口',
  `target` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作对象（模块+资源标识）',
  `params` text COLLATE utf8mb4_unicode_ci COMMENT '请求参数（脱敏后）',
  `result` tinyint NOT NULL COMMENT '结果：1成功 / 0失败',
  `error_msg` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `ip` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作IP',
  `event_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '一次操作的稳定审计事件ID',
  `audit_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'COMPLETED' COMMENT 'STARTED/COMPLETED',
  `retention_until` datetime DEFAULT NULL COMMENT '最短留存截止时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_operation_log_event` (`event_id`),
  KEY `idx_sys_operation_log_user` (`user_id`),
  KEY `idx_sys_operation_log_time` (`create_time`),
  KEY `idx_sys_operation_log_tenant` (`tenant_id`),
  KEY `idx_sys_operation_log_retention` (`audit_status`,`retention_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志（含登录/登出日志）';

-- ----------------------------------------------------------------------------
-- sys_permission
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父权限ID（支持菜单树，0=根节点）',
  `perm_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '权限/菜单名称',
  `perm_code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '权限标识（如 mcp:add / skill:delete）',
  `type` tinyint NOT NULL COMMENT '类型：1菜单 / 2按钮 / 3接口',
  `path` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '前端路由 / 接口路径',
  `icon` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图标库图标名或上传图片URL（按 icon_type 区分）',
  `icon_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'library' COMMENT '图标类型：library=图标库图标名，image=上传图片URL',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_permission_code` (`perm_code`),
  KEY `idx_sys_permission_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限/菜单（树形）';

-- ----------------------------------------------------------------------------
-- sys_role
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色名称',
  `role_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色编码（唯一，如 super_admin）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 / 1启用',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `data_scope` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SELF' COMMENT '数据范围：ALL全部租户 / TENANT本租户全部 / SELF仅本人创建',
  `control_plane` tinyint NOT NULL DEFAULT '0' COMMENT '是否控制面角色：0否 / 1是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_tenant_code` (`tenant_id`,`role_code`),
  KEY `idx_sys_role_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

-- ----------------------------------------------------------------------------
-- sys_role_permission
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_permission` (`role_id`,`permission_id`),
  KEY `idx_sys_role_permission_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-权限关联';

-- ----------------------------------------------------------------------------
-- sys_tenant
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码（业务主键，出现在 API Key 映射/日志/指标标签里）',
  `tenant_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户名称',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE正常 / SUSPENDED冻结 / TERMINATED退租',
  `contact_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系人',
  `contact_phone` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系电话',
  `contact_email` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系邮箱',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `expire_time` datetime DEFAULT NULL COMMENT '到期时间（空=不限期）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `access_epoch` bigint NOT NULL DEFAULT '0' COMMENT '访问版本；冻结、恢复、退租或主动撤权时递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_tenant_code` (`tenant_code`),
  KEY `idx_sys_tenant_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户主数据';

-- ----------------------------------------------------------------------------
-- sys_tenant_access_publish_task
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_tenant_access_publish_task` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `seq` bigint NOT NULL AUTO_INCREMENT COMMENT '严格写入顺序',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `tenant_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `access_epoch` bigint NOT NULL,
  `operation` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PROVISION/EXPIRY_CHANGE/STATUS_CHANGE/SESSION_REVOKE/OFFBOARD',
  `session_revocation_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'NOT_REQUIRED/EPOCH_ENFORCED',
  `channel_disable_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'NOT_REQUIRED/COMPLETED',
  `channels_disabled_count` int NOT NULL DEFAULT '0',
  `expire_time` datetime DEFAULT NULL,
  `data_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at_ms` bigint NOT NULL,
  `active_lease_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理中写租户ID；唯一键保证同租户仅一个发布者',
  `lease_owner` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until_ms` bigint NOT NULL DEFAULT '0',
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `published_at_ms` bigint DEFAULT NULL,
  `created_at_ms` bigint NOT NULL,
  `updated_at_ms` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_access_publish_seq` (`seq`),
  UNIQUE KEY `uk_tenant_access_publish_epoch` (`tenant_id`,`access_epoch`),
  UNIQUE KEY `uk_tenant_access_publish_active_lease` (`active_lease_key`),
  KEY `idx_tenant_access_publish_due` (`status`,`next_attempt_at_ms`,`lease_until_ms`),
  KEY `idx_tenant_access_publish_tenant` (`tenant_id`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户访问快照可靠发布任务';

-- ----------------------------------------------------------------------------
-- sys_user
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录账号',
  `password` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码（BCrypt 加密存储，LDAP 账号为 NULL）',
  `nickname` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
  `login_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LOCAL' COMMENT '账号来源：LOCAL本地账号 / LDAP域账号(OA单点登录)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 / 1启用',
  `approval_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'APPROVED' COMMENT '注册审核状态：PENDING/APPROVED/REJECTED',
  `approval_by` bigint DEFAULT NULL COMMENT '最近一次审核人 sys_user.id',
  `approval_time` datetime DEFAULT NULL COMMENT '最近一次审核时间',
  `approval_remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次审核说明或拒绝原因',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后登录 IP',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `level_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '配额等级编码（空=默认档），见客服端库 cw_subject_quota_level',
  `auth_epoch` bigint NOT NULL DEFAULT '0' COMMENT '认证版本；禁用、删号、改密或角色变化时递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_user_username` (`username`),
  KEY `idx_sys_user_tenant` (`tenant_id`),
  KEY `idx_sys_user_approval` (`tenant_id`,`approval_status`,`deleted`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台用户';

-- ----------------------------------------------------------------------------
-- sys_user_role
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_user_role` (`user_id`,`role_id`),
  KEY `idx_sys_user_role_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-角色关联';

-- ----------------------------------------------------------------------------
-- workbench_site
-- ----------------------------------------------------------------------------
CREATE TABLE `workbench_site` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '系统名称',
  `category` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类：如 git/jenkins/oa',
  `url` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '访问地址',
  `account` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登录账号',
  `password` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登录密码（AES-GCM 密文，永不明文返回）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：0禁用 / 1启用',
  `username_selector` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名输入框 CSS 选择器，留空用启发式',
  `password_selector` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码输入框 CSS 选择器，留空用 input[type=password]',
  `submit_selector` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登录按钮 CSS 选择器，留空用启发式',
  `fill_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'auto' COMMENT '填充模式：auto=原生setter一次性 / typing=逐字模拟（顽固React如Kibana）',
  `submit_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'click' COMMENT '提交方式：click=点按钮 / formSubmit=表单提交',
  `init_delay_ms` int NOT NULL DEFAULT '500' COMMENT '进页面后开始查找元素的延迟毫秒',
  `submit_delay_ms` int NOT NULL DEFAULT '300' COMMENT '填完到点击提交的延迟毫秒',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workbench_site_name` (`name`),
  KEY `idx_workbench_site_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内网工作台系统账号本';

-- ----------------------------------------------------------------------------
-- workbench_token
-- ----------------------------------------------------------------------------
CREATE TABLE `workbench_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '令牌所属用户ID',
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '令牌用途备注',
  `token_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '令牌明文的 SHA-256 十六进制，明文只在创建时返回一次',
  `token_prefix` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '令牌前缀（如 wbt_ab12cd34），列表展示用',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间，NULL 表示永不过期',
  `last_used_time` datetime DEFAULT NULL COMMENT '最近一次使用时间',
  `revoked` tinyint NOT NULL DEFAULT '0' COMMENT '是否已吊销：0否 / 1是',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0正常 / 1删除',
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workbench_token_hash` (`token_hash`),
  KEY `idx_workbench_token_user` (`user_id`),
  KEY `idx_workbench_token_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内网工作台个人访问令牌';
