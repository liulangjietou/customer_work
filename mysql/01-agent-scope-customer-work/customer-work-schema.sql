-- =============================================================================
-- AgentScope 智能客服系统 · 会话持久化 MySQL 建库建表脚本
-- =============================================================================
-- 说明：
--   1. MysqlSession 在 autoCreate=true 时会自动建库建表，本脚本用于手工初始化 /
--      DBA 审核 / 受限权限环境（生产 DB 账号通常无建库权限）。
--   2. 会话表结构与框架 io.agentscope.core.session.mysql.MysqlSession 内置 DDL 一致；
--      cw_* 业务表由 starter 的 SchemaInitializer（MyBatis-Plus 持久层）在 auto-create=true 时执行
--      classpath:customerwork/schema/customer-work-schema.sql 建表，本脚本的 cw_* DDL 与之保持一致。
--   3. 连接信息（默认）：host=localhost:3306, user=root, password=root,
--      database=agent_scope_customer_work。
--
-- 执行：mysql -h localhost -u root -proot --default-character-set=utf8mb4 < mysql/01-agent-scope-customer-work/customer-work-schema.sql
-- =============================================================================


CREATE DATABASE IF NOT EXISTS `agent_scope_customer_work`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `agent_scope_customer_work`;

-- 会话状态表：以 (session_id, state_key, item_index) 为主键，
-- state_data 存储序列化后的状态 JSON（短期记忆、PlanNotebook、Toolkit 状态等）。
CREATE TABLE IF NOT EXISTS `agentscope_sessions` (
    `session_id`  VARCHAR(255) NOT NULL,
    `state_key`   VARCHAR(255) NOT NULL,
    `item_index`  INT          NOT NULL DEFAULT 0,
    `state_data`  LONGTEXT     NOT NULL,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`, `state_key`, `item_index`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 合规审计日志表（JdbcAuditSink 结构化存储）
-- =============================================================================
-- 说明：由 JdbcAuditSink 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。

CREATE TABLE IF NOT EXISTS `cw_audit_log` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `event_type`  VARCHAR(64) NOT NULL COMMENT '事件类型: tool-call / final-answer / error',
    `agent_name`  VARCHAR(128) DEFAULT '' COMMENT 'Agent 名称',
    `event_data`  TEXT COMMENT '结构化事件字段 JSON',
    `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    INDEX `idx_audit_type` (`event_type`),
    INDEX `idx_audit_created` (`created_at`),
    INDEX `idx_audit_agent` (`agent_name`),
    INDEX `idx_audit_log_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 人工审批工单表（JdbcApprovalStore 结构化存储，human-approval.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcApprovalStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。退款等资金动作的审批单持久化于此，
--       保证应用重启 / 多实例部署下审批单不丢失。

CREATE TABLE IF NOT EXISTS `cw_approval` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`                        VARCHAR(64) PRIMARY KEY COMMENT '审批单号',
    `type`                      VARCHAR(32) NOT NULL COMMENT '审批类型：REFUND 等',
    `session_id`                VARCHAR(128) COMMENT '关联会话',
    `order_id`                  VARCHAR(64) COMMENT '关联订单号',
    `amount`                    VARCHAR(32) COMMENT '涉及金额',
    `reason`                    TEXT COMMENT '诉求原因',
    `created_at_ms`             BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `status`                    VARCHAR(16) NOT NULL COMMENT 'PENDING/APPROVED/DENIED',
    `operator`                  VARCHAR(64) COMMENT '决策操作员',
    `decision_note`             TEXT COMMENT '决策备注',
    `decided_at_ms`             BIGINT DEFAULT 0 COMMENT '决策时间戳（毫秒）',
    `execution_status`          VARCHAR(24) DEFAULT 'NOT_APPLICABLE' COMMENT '下游执行状态',
    `execution_failure_reason`  TEXT COMMENT '下游执行失败原因',
    `execution_attempts`        INT DEFAULT 0 COMMENT '下游执行尝试次数',
    INDEX `idx_approval_status` (`status`),
    INDEX `idx_approval_created` (`created_at_ms`),
    INDEX `idx_approval_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 多轮槽位收集进度表（JdbcSlotFillingStore 结构化存储，slot-filling.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcSlotFillingStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。保证多轮信息收集（如退款表单：订单号→原因）
--       中途重启可续填，用户无需从头重答。

CREATE TABLE IF NOT EXISTS `cw_slot_filling_progress` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `progress_key`    VARCHAR(191) PRIMARY KEY COMMENT '收集进度键：sessionId:formName',
    `asking`          VARCHAR(64) COMMENT '当前追问的槽位名',
    `collected_json`  TEXT COMMENT '已收集槽位值（JSON）',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    INDEX `idx_slot_filling_progress_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 对话阶段状态机表（JdbcDialogStageStore 结构化存储，dialog.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcDialogStageStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。多实例部署下跨实例共享同一份会话阶段，
--       避免负载均衡到不同实例导致阶段状态"归零"回 GREETING。

CREATE TABLE IF NOT EXISTS `cw_dialog_stage` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `session_id`  VARCHAR(191) PRIMARY KEY COMMENT '会话 ID',
    `stage`       VARCHAR(24) NOT NULL COMMENT '当前对话阶段：GREETING/COLLECTING/PROCESSING/CONFIRMING/ESCALATED',
    `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    INDEX `idx_dialog_stage_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 历史人机切换工单归档表（P1-03 起生产只读，V17 归并到 cw_ticket）
-- =============================================================================
-- 说明：仅为 V17 接管存量数据保留。所有新建、接单、结案、路由和 SLA 均使用 cw_ticket；
--       新生产代码不得再写本表。

CREATE TABLE IF NOT EXISTS `cw_handoff_ticket` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`                VARCHAR(64) PRIMARY KEY COMMENT '工单号',
    `session_id`        VARCHAR(128) COMMENT '关联会话',
    `reason`            TEXT COMMENT '转人工原因',
    `created_at_ms`     BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `status`            VARCHAR(16) NOT NULL COMMENT 'PENDING/CLAIMED/RESOLVED',
    `claimed_by`        VARCHAR(64) COMMENT '接单坐席',
    `claimed_at_ms`     BIGINT DEFAULT 0 COMMENT '接单时间戳（毫秒）',
    `resolution_note`   TEXT COMMENT '处理结果备注',
    `resolved_at_ms`    BIGINT DEFAULT 0 COMMENT '结案时间戳（毫秒）',
    `category`          VARCHAR(64) COMMENT '工单分类（LLM 分类，可空）',
    `required_skill`    VARCHAR(64) COMMENT '所需坐席技能标签（LLM 分类，可空）',
    `priority`          VARCHAR(16) COMMENT '优先级 LOW/MEDIUM/HIGH/URGENT（LLM 分类，可空）',
    `emotion`           VARCHAR(32) COMMENT '用户情绪（LLM 分类，可空）',
    `suggested_assignees` TEXT COMMENT '推荐坐席列表 JSON（HITL 推荐，人工点选非自动派单，可空）',
    INDEX `idx_handoff_status` (`status`),
    INDEX `idx_handoff_created` (`created_at_ms`),
    INDEX `idx_handoff_ticket_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 消息级用户反馈表（JdbcFeedbackStore 结构化存储，feedback.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcFeedbackStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。用户对 /chat 回复的点赞/点踩，DOWN 类型自动沉淀
--       到 FactLog 供离线复盘，是数据飞轮除系统主动质检外的另一条用户主动输入通道。
--       同一 message_id 重复提交按最新一次覆盖（用户改变主意允许更正）。

CREATE TABLE IF NOT EXISTS `cw_message_feedback` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `message_id`     VARCHAR(64) PRIMARY KEY COMMENT '被反馈的消息ID',
    `session_id`     VARCHAR(128) COMMENT '所属会话',
    `type`           VARCHAR(8) NOT NULL COMMENT 'UP/DOWN',
    `comment`        TEXT COMMENT '文字说明',
    `created_at_ms`  BIGINT NOT NULL COMMENT '提交时间戳（毫秒，重复提交取最新）',
    INDEX `idx_feedback_session` (`session_id`),
    INDEX `idx_feedback_type` (`type`),
    INDEX `idx_message_feedback_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 智能客服工单系统（ticket / user / chatlog + JDBC 工具后端 演示表）
-- =============================================================================
-- 说明：以下 12 张表分别由 JdbcTicketStore / JdbcUserAccountStore / JdbcChatMessageStore /
--       JdbcOrderBackend / JdbcProductBackend / JdbcAfterSalesBackend / JdbcMemberBackend /
--       JdbcComplaintBackend / JdbcKnowledgeBackend 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境；列/索引与各 JdbcStore 的 ensureTable 一致。
--       cw_product / cw_order / cw_refund / cw_member / cw_complaint / cw_knowledge 的种子数据与各
--       Jdbc 后端 INSERT IGNORE 的种子完全一致（含 Mock 示例订单 20260613001 / 20260613002），
--       保证从 Mock 切到 JDBC 后系统提示词示例连续。

-- 终端用户账户表（cw_user）：登录凭据以 BCrypt 哈希存储。
CREATE TABLE IF NOT EXISTS `cw_user` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             VARCHAR(64) PRIMARY KEY COMMENT '用户ID',
    `username`       VARCHAR(64) NOT NULL COMMENT '用户名',
    `password_hash`  VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname`       VARCHAR(64) COMMENT '昵称',
    `phone`          VARCHAR(32) COMMENT '手机号',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `avatar_url`     VARCHAR(255) COMMENT '头像访问URL（相对路径，可为空）',
    `level_code`     VARCHAR(64) DEFAULT NULL COMMENT '配额等级编码（空=默认档），见 cw_subject_quota_level',
    `session_epoch`  BIGINT NOT NULL DEFAULT 0 COMMENT '用户会话撤销版本',
    UNIQUE KEY `uk_user_username` (`tenant_id`, `username`),
    INDEX `idx_user_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 客服工单主表（cw_ticket）：完整生命周期状态机（AI_SERVING→WAITING_AGENT→PROCESSING→...→CLOSED）。
CREATE TABLE IF NOT EXISTS `cw_ticket` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`              VARCHAR(64) PRIMARY KEY COMMENT '工单号',
    `session_id`      VARCHAR(128) NOT NULL COMMENT '关联会话',
    `user_id`         VARCHAR(64) NOT NULL COMMENT '发起用户',
    `title`           VARCHAR(255) COMMENT '工单标题',
    `category`        VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT '分类',
    `priority`        VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '优先级',
    `status`          VARCHAR(32) NOT NULL COMMENT '状态机状态',
    `assignee`        VARCHAR(64) COMMENT '当前处理坐席',
    `handoff_reason`  VARCHAR(255) COMMENT '转人工原因',
    `resolve_note`    TEXT COMMENT '处理结论/备注',
    `routing_category` VARCHAR(64) COMMENT '智能路由分类原文',
    `required_skill`   VARCHAR(64) COMMENT '所需坐席技能',
    `routing_priority` VARCHAR(16) COMMENT '智能路由优先级原文',
    `emotion`          VARCHAR(32) COMMENT '用户情绪',
    `suggested_assignees` TEXT COMMENT '推荐坐席列表 JSON（HITL 展示）',
    `reopen_count`    INT NOT NULL DEFAULT 0 COMMENT '重开次数',
    `created_at_ms`   BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`   BIGINT NOT NULL COMMENT '更新时间戳（毫秒）',
    `handoff_at_ms`   BIGINT DEFAULT 0 COMMENT '最近转人工时间戳（毫秒）',
    `claimed_at_ms`   BIGINT DEFAULT 0 COMMENT '接单时间戳（毫秒）',
    `resolved_at_ms`  BIGINT DEFAULT 0 COMMENT '解决时间戳（毫秒）',
    `closed_at_ms`    BIGINT DEFAULT 0 COMMENT '关闭时间戳（毫秒）',
    `last_user_active_at_ms` BIGINT DEFAULT 0 COMMENT '用户最后活跃时间戳（毫秒，空闲超时巡检基准）',
    INDEX `idx_ticket_session` (`session_id`),
    INDEX `idx_ticket_user` (`user_id`, `created_at_ms`),
    INDEX `idx_ticket_status` (`status`, `updated_at_ms`),
    INDEX `idx_ticket_assignee` (`assignee`, `status`),
    INDEX `idx_ticket_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 工单事件轨迹表（cw_ticket_event）：每次状态流转一条，不可变审计。
CREATE TABLE IF NOT EXISTS `cw_ticket_event` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件自增主键',
    `ticket_id`      VARCHAR(64) NOT NULL COMMENT '所属工单号',
    `event_type`     VARCHAR(32) NOT NULL COMMENT '事件类型',
    `from_status`    VARCHAR(32) COMMENT '流转前状态',
    `to_status`      VARCHAR(32) COMMENT '流转后状态',
    `actor_type`     VARCHAR(16) NOT NULL COMMENT '动作发起方类型',
    `actor_id`       VARCHAR(64) COMMENT '动作发起方标识',
    `note`           VARCHAR(500) COMMENT '备注',
    `created_at_ms`  BIGINT NOT NULL COMMENT '事件时间戳（毫秒）',
    INDEX `idx_ticket_event` (`ticket_id`, `id`),
    INDEX `idx_ticket_event_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 聊天消息留痕表（cw_chat_message）：会话/工单双维度，自增主键用于游标翻页。
CREATE TABLE IF NOT EXISTS `cw_chat_message` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（游标翻页）',
    `message_id`     VARCHAR(64) NOT NULL COMMENT '业务消息号 MSG-<uuid>',
    `session_id`     VARCHAR(128) NOT NULL COMMENT '所属会话',
    `ticket_id`      VARCHAR(64) COMMENT '关联工单号（可空）',
    `sender_type`    VARCHAR(16) NOT NULL COMMENT '发送方类型 USER/BOT/AGENT/SYSTEM',
    `sender_id`      VARCHAR(64) COMMENT '发送方标识（可空）',
    `content`        TEXT NOT NULL COMMENT '消息内容',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    UNIQUE KEY `uk_chat_message_id` (`message_id`),
    INDEX `idx_chat_session` (`session_id`, `id`),
    INDEX `idx_chat_ticket` (`ticket_id`, `id`),
    INDEX `idx_chat_message_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 智能体调用主记录表（cw_agent_call_log）：每次调用一行，含分段耗时冗余汇总（MybatisAgentCallLogStore）。
CREATE TABLE IF NOT EXISTS `cw_agent_call_log` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `request_id`     VARCHAR(64) NOT NULL DEFAULT '' COMMENT '请求ID（全链路关联）',
    `user_id`        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '用户ID（ctx.userId）',
    `username`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '用户名',
    `agent_code`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '智能体编码',
    `agent_name`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '智能体名称',
    `session_id`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '会话ID',
    `session_type`   VARCHAR(32) NOT NULL DEFAULT 'CHAT' COMMENT '会话类型 CHAT/VIBE_CODING',
    `question`       MEDIUMTEXT COMMENT '用户问题',
    `answer`         MEDIUMTEXT COMMENT '智能体回答',
    `start_time`     BIGINT NOT NULL COMMENT '调用开始时间戳（毫秒）',
    `end_time`       BIGINT NOT NULL COMMENT '调用结束时间戳（毫秒）',
    `duration_ms`    BIGINT NOT NULL DEFAULT 0 COMMENT '总耗时（毫秒）',
    `model_ms`       BIGINT NOT NULL DEFAULT 0 COMMENT 'MODEL段耗时合计（毫秒）',
    `tool_ms`        BIGINT NOT NULL DEFAULT 0 COMMENT 'TOOL段耗时合计（毫秒）',
    `mcp_ms`         BIGINT NOT NULL DEFAULT 0 COMMENT 'MCP段耗时合计（毫秒）',
    `skill_ms`       BIGINT NOT NULL DEFAULT 0 COMMENT 'SKILL段耗时合计（毫秒）',
    `segment_count`  INT NOT NULL DEFAULT 0 COMMENT '分段总数',
    `input_tokens`   BIGINT DEFAULT NULL COMMENT '请求级输入token合计（缺失为NULL）',
    `output_tokens`  BIGINT DEFAULT NULL COMMENT '请求级输出token合计（缺失为NULL）',
    `total_tokens`   BIGINT DEFAULT NULL COMMENT '请求级总token合计（缺失为NULL）',
    `cached_tokens`  BIGINT DEFAULT NULL COMMENT '命中缓存的输入token（input_tokens的子集，不计入total）',
    `model_reported_ms` BIGINT DEFAULT NULL COMMENT '模型自报耗时合计（毫秒），与model_ms之差=网络/排队开销',
    `model_cost_amount` DECIMAL(30,14) DEFAULT NULL COMMENT '本次调用已结算模型金额',
    `model_cost_currency` VARCHAR(16) DEFAULT NULL COMMENT '单币种结算币种',
    `model_cost_status` VARCHAR(24) NOT NULL DEFAULT 'NO_MODEL' COMMENT 'COMPLETE/PARTIAL/UNAVAILABLE/MULTI_CURRENCY/NO_MODEL',
    `model_segment_count` INT NOT NULL DEFAULT 0 COMMENT '模型分段数',
    `settled_cost_segment_count` INT NOT NULL DEFAULT 0 COMMENT '已结算模型分段数',
    `unsettled_cost_segment_count` INT NOT NULL DEFAULT 0 COMMENT '未结算模型分段数',
    `trace_id`       VARCHAR(32) DEFAULT NULL COMMENT 'W3C trace-id，关联 OTel/Tempo',
    `runtime_revision` VARCHAR(64) DEFAULT NULL COMMENT '实例实际应用的运行配置发布修订',
    `runtime_content_hash` CHAR(64) DEFAULT NULL COMMENT '运行配置内容摘要，关联发布任务与实例ACK',
    `version_binding_json` JSON DEFAULT NULL COMMENT '模型/提示词/Agent/知识库/工具版本绑定（不含密钥）',
    `replay_snapshot_json` JSON DEFAULT NULL COMMENT '脱敏模型参数、RAG与工具重放事实',
    `experiment_id` BIGINT DEFAULT NULL COMMENT '在线实验ID',
    `experiment_revision` INT DEFAULT NULL COMMENT '不可变实验修订号',
    `experiment_arm` VARCHAR(16) DEFAULT NULL COMMENT 'CONTROL/TREATMENT',
    `experiment_deployment_id` BIGINT DEFAULT NULL COMMENT '实际命中的模型部署ID',
    `experiment_bucket` INT DEFAULT NULL COMMENT '稳定分桶0..9999；无可信主体时为空',
    `success`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '整次调用是否成功',
    `error_msg`      VARCHAR(1024) COMMENT '失败原因',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    INDEX `idx_call_request` (`request_id`),
    INDEX `idx_call_username` (`username`),
    INDEX `idx_call_agent_code` (`agent_code`),
    INDEX `idx_call_session` (`session_id`),
    INDEX `idx_call_start` (`start_time`),
    INDEX `idx_call_trace_id` (`trace_id`),
    INDEX `idx_call_runtime_revision` (`runtime_revision`),
    INDEX `idx_call_experiment_arm` (`experiment_id`, `experiment_revision`, `experiment_arm`, `start_time`),
    INDEX `idx_agent_call_cost_window` (`tenant_id`, `start_time`, `model_cost_status`),
    INDEX `idx_agent_call_log_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 智能体调用分段明细表（cw_agent_call_segment）：一次调用的每段耗时一行（MybatisAgentCallLogStore）。
CREATE TABLE IF NOT EXISTS `cw_agent_call_segment` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `call_log_id`    BIGINT NOT NULL COMMENT '所属主记录ID',
    `seq`            INT NOT NULL COMMENT '调用内分段序号（从1起）',
    `kind`           VARCHAR(16) NOT NULL COMMENT '分段类别 MODEL/TOOL/MCP/SKILL',
    `name`           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '分段名称（模型名/工具名）',
    `start_time`     BIGINT NOT NULL COMMENT '分段开始时间戳（毫秒）',
    `duration_ms`    BIGINT NOT NULL DEFAULT 0 COMMENT '分段耗时（毫秒）',
    `input_tokens`   BIGINT DEFAULT NULL COMMENT '输入token（仅MODEL段，缺失为NULL）',
    `output_tokens`  BIGINT DEFAULT NULL COMMENT '输出token（仅MODEL段，缺失为NULL）',
    `cached_tokens`  BIGINT DEFAULT NULL COMMENT '命中缓存的输入token（仅MODEL段）',
    `model_reported_ms` BIGINT DEFAULT NULL COMMENT '模型自报耗时（毫秒，仅MODEL段）',
    `provider`       VARCHAR(64) DEFAULT NULL COMMENT '实际模型供应商',
    `deployment_id`  BIGINT DEFAULT NULL COMMENT '实际模型部署ID',
    `model_name`     VARCHAR(191) DEFAULT NULL COMMENT '实际模型名',
    `price_id`       BIGINT DEFAULT NULL COMMENT '调用时冻结的价目ID',
    `currency`       VARCHAR(16) DEFAULT NULL COMMENT '调用时冻结的币种',
    `input_unit_price` DECIMAL(20,8) DEFAULT NULL COMMENT '调用时输入单价（每百万token）',
    `output_unit_price` DECIMAL(20,8) DEFAULT NULL COMMENT '调用时输出单价（每百万token）',
    `cached_unit_price` DECIMAL(20,8) DEFAULT NULL COMMENT '调用时缓存输入单价（每百万token）',
    `pricing_status` VARCHAR(16) NOT NULL DEFAULT 'UNPRICED' COMMENT 'PRICED/UNPRICED',
    `cost_amount` DECIMAL(30,14) DEFAULT NULL COMMENT '按冻结价目结算的模型金额',
    `cost_currency` VARCHAR(16) DEFAULT NULL COMMENT '结算币种',
    `cost_status` VARCHAR(24) NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT 'SETTLED/UNPRICED/USAGE_MISSING/USAGE_INVALID/NOT_APPLICABLE',
    `success`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '分段是否成功',
    `error_msg`      VARCHAR(1024) COMMENT '失败原因',
    `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    INDEX `idx_segment_call_log` (`call_log_id`, `seq`),
    INDEX `idx_agent_call_segment_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- token 统计补列（2026-07-28）：CREATE TABLE IF NOT EXISTS 对已存在的表不加列，
-- 旧库存量表需手工执行下列 ALTER 补列（MySQL 无 ADD COLUMN IF NOT EXISTS，重复执行报 Duplicate column 可忽略）：
-- ALTER TABLE `cw_agent_call_log`
--   ADD COLUMN `cached_tokens` BIGINT DEFAULT NULL COMMENT '命中缓存的输入token（input_tokens的子集，不计入total）' AFTER `total_tokens`,
--   ADD COLUMN `model_reported_ms` BIGINT DEFAULT NULL COMMENT '模型自报耗时合计（毫秒），与model_ms之差=网络/排队开销' AFTER `cached_tokens`;
-- ALTER TABLE `cw_agent_call_segment`
--   ADD COLUMN `cached_tokens` BIGINT DEFAULT NULL COMMENT '命中缓存的输入token（仅MODEL段）' AFTER `output_tokens`,
--   ADD COLUMN `model_reported_ms` BIGINT DEFAULT NULL COMMENT '模型自报耗时（毫秒，仅MODEL段）' AFTER `cached_tokens`;

-- 对话附件表（cw_chat_attachment）：上传附件落盘 + 落库，解析文本可追溯（MybatisAttachmentStore）。
CREATE TABLE IF NOT EXISTS `cw_chat_attachment` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             VARCHAR(64) NOT NULL COMMENT '附件ID(UUID)',
    `session_id`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '会话ID',
    `message_id`     VARCHAR(64) NOT NULL DEFAULT '' COMMENT '绑定的用户消息ID（框架Msg.id，空=未绑定）',
    `uploader`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '上传者标识',
    `channel`        VARCHAR(32) NOT NULL DEFAULT '' COMMENT '来源渠道:user_chat/admin_chat/vibecoding',
    `file_name`      VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `extension`      VARCHAR(16) NOT NULL DEFAULT '' COMMENT '扩展名',
    `mime_type`      VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'MIME类型',
    `file_size`      BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `storage_path`   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '落盘相对路径',
    `parse_status`   VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态:SUCCESS/FAILED',
    `parsed_text`    MEDIUMTEXT NULL COMMENT '解析出的文本',
    `error_message`  VARCHAR(512) NULL COMMENT '解析失败原因',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_cw_attachment_session` (`session_id`),
    INDEX `idx_cw_attachment_created` (`created_at`),
    INDEX `idx_chat_attachment_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 商品表（cw_product）：JdbcProductBackend 演示表。
CREATE TABLE IF NOT EXISTS `cw_product` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `product_id`   VARCHAR(32) PRIMARY KEY COMMENT '商品ID',
    `name`         VARCHAR(128) NOT NULL COMMENT '商品名称',
    `category`     VARCHAR(64) COMMENT '品类',
    `price`        DECIMAL(10,2) NOT NULL COMMENT '价格',
    `stock`        INT NOT NULL DEFAULT 0 COMMENT '库存',
    `description`  VARCHAR(500) COMMENT '商品描述',
    `promotion`    VARCHAR(255) COMMENT '优惠活动',
    `status`       VARCHAR(16) NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE/OFF_SALE',
    `created_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    INDEX `idx_product_category` (`category`),
    INDEX `idx_product_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_product` (`tenant_id`, `product_id`, `name`, `category`, `price`, `stock`, `description`, `promotion`, `status`) VALUES
('default', 'P001', '旗舰款无线降噪耳机', '耳机', 299.00, 100, '旗舰款无线降噪耳机，蓝牙 5.3，续航 30 小时，支持多点连接，颜色 黑/白，质保 1 年', '满 300 减 50；可叠加新人券 20 元；下单送收纳包', 'ON_SALE'),
('default', 'P002', '运动防汗蓝牙耳机', '耳机', 199.00, 50, '运动防汗蓝牙耳机，IPX5 级防水，佩戴稳固，适合健身运动', '限时直降 30 元，晒单再返 10 元', 'ON_SALE'),
('default', 'P003', '商务降噪头戴耳机', '耳机', 599.00, 0, '商务降噪头戴耳机，主动降噪，麦克风通话清晰，续航 40 小时', '', 'ON_SALE');

-- 订单表（cw_order）：JdbcOrderBackend 演示表。种子含 Mock 示例订单，状态/金额/下单日期与 Mock 一致。
CREATE TABLE IF NOT EXISTS `cw_order` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `order_id`         VARCHAR(32) PRIMARY KEY COMMENT '订单号',
    `user_id`          VARCHAR(64) NOT NULL COMMENT '下单用户',
    `product_id`       VARCHAR(32) NOT NULL COMMENT '商品ID',
    `product_name`     VARCHAR(128) COMMENT '商品名称',
    `amount`           DECIMAL(10,2) NOT NULL COMMENT '订单金额',
    `status`           VARCHAR(32) NOT NULL COMMENT '订单状态',
    `receiver_addr`    VARCHAR(255) COMMENT '收货地址',
    `logistics_trace`  TEXT COMMENT '物流轨迹',
    `created_at_ms`    BIGINT NOT NULL COMMENT '下单时间戳（毫秒）',
    INDEX `idx_order_user` (`user_id`, `created_at_ms`),
    INDEX `idx_order_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_order` (`tenant_id`, `order_id`, `user_id`, `product_id`, `product_name`, `amount`, `status`, `receiver_addr`, `logistics_trace`, `created_at_ms`) VALUES
('default', '20260613001', 'U-demo-1', 'P001', '旗舰款无线降噪耳机', 299.00, '已发货', '北京市朝阳区建国路 88 号', '[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。', 1781049600000),
('default', '20260613002', 'U-demo-1', 'P003', '商务降噪头戴耳机', 1599.00, '已签收', '上海市浦东新区世纪大道 100 号', '[5-18 已揽收]→[5-19 运输中]→[5-20 已签收]。', 1779235200000),
('default', '20260613003', 'U-demo-2', 'P002', '运动防汗蓝牙耳机', 199.00, '待发货', '广州市天河区体育西路 1 号', '[6-13 已下单，仓库备货中]', 1781308800000),
('default', '20260613004', 'U-demo-2', 'P001', '旗舰款无线降噪耳机', 299.00, '已退款', '深圳市南山区科技园路 5 号', '[6-08 已揽收]→[6-09 用户取消]→[6-10 已退款]', 1780876800000);

-- 售后工单表（cw_refund）：JdbcAfterSalesBackend 演示表。退款/退货/换货共表，submitRefund 只落 PENDING 待人工复核（资金红线）。
CREATE TABLE IF NOT EXISTS `cw_refund` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `refund_no`      VARCHAR(64) PRIMARY KEY COMMENT '售后工单号',
    `order_id`       VARCHAR(32) NOT NULL COMMENT '关联订单号',
    `type`           VARCHAR(16) NOT NULL COMMENT '类型：REFUND/RETURN/EXCHANGE',
    `status`         VARCHAR(16) NOT NULL COMMENT '状态：PENDING/APPROVED/DENIED',
    `amount`         DECIMAL(10,2) COMMENT '退款金额（退货/换货可空）',
    `reason`         VARCHAR(500) COMMENT '诉求原因',
    `new_spec`       VARCHAR(128) COMMENT '换货目标规格',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_refund_order` (`order_id`, `created_at_ms`),
    INDEX `idx_refund_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_refund` (`tenant_id`, `refund_no`, `order_id`, `type`, `status`, `amount`, `reason`, `new_spec`, `created_at_ms`) VALUES
('default', 'RF-seed-20260613004', '20260613004', 'REFUND', 'APPROVED', 299.00, '七天无理由退款', NULL, 1781049600000);

-- 发票申请表（cw_invoice_request）：JdbcAfterSalesBackend 演示表。requestInvoice 真实落库。
CREATE TABLE IF NOT EXISTS `cw_invoice_request` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '发票申请自增主键',
    `order_id`       VARCHAR(32) NOT NULL COMMENT '关联订单号',
    `invoice_title`  VARCHAR(255) NOT NULL COMMENT '发票抬头',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/ISSUED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_invoice_order` (`order_id`, `created_at_ms`),
    INDEX `idx_invoice_request_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 会员表（cw_member）：JdbcMemberBackend 演示表。种子 U-demo-1 与 Mock 数据一致（黄金会员/积分 1280）。
CREATE TABLE IF NOT EXISTS `cw_member` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `member_id`        VARCHAR(64) PRIMARY KEY COMMENT '会员ID（对应用户ID）',
    `level`            VARCHAR(32) NOT NULL COMMENT '会员等级',
    `points`           INT NOT NULL DEFAULT 0 COMMENT '当前积分',
    `points_expiring`  INT NOT NULL DEFAULT 0 COMMENT '本月底到期积分',
    `benefits`         VARCHAR(255) COMMENT '等级权益',
    `next_level`       VARCHAR(32) COMMENT '下一等级',
    `upgrade_gap`      DECIMAL(10,2) DEFAULT 0 COMMENT '升级所需再消费金额',
    `phone`            VARCHAR(32) COMMENT '注册手机号',
    `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    INDEX `idx_member_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_member` (`tenant_id`, `member_id`, `level`, `points`, `points_expiring`, `benefits`, `next_level`, `upgrade_gap`, `phone`) VALUES
('default', 'U-demo-1', '黄金会员', 1280, 200, '免运费、专属客服、生日双倍积分', '铂金', 500.00, '138****0001'),
('default', 'U-demo-2', '白银会员', 320, 0, '满额包邮、积分商城兑换', '黄金', 800.00, '139****0002');

-- 会员账户问题处理日志表（cw_member_account_log）：JdbcMemberBackend 演示表。resolveAccountIssue 落一条处理日志。
CREATE TABLE IF NOT EXISTS `cw_member_account_log` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '处理日志自增主键',
    `issue`          VARCHAR(255) NOT NULL COMMENT '账户问题描述',
    `handling`       VARCHAR(500) COMMENT '处置话术',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_account_log_created` (`created_at_ms`),
    INDEX `idx_member_account_log_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 投诉工单表（cw_complaint）：JdbcComplaintBackend 演示表。种子 CP-seed-0001 支撑 queryComplaint 演示。
CREATE TABLE IF NOT EXISTS `cw_complaint` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `complaint_no`   VARCHAR(64) PRIMARY KEY COMMENT '投诉工单号',
    `order_id`       VARCHAR(32) COMMENT '关联订单号（可空）',
    `content`        TEXT COMMENT '投诉内容',
    `status`         VARCHAR(16) NOT NULL COMMENT '状态：PROCESSING/RESOLVED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_complaint_order` (`order_id`, `created_at_ms`),
    INDEX `idx_complaint_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_complaint` (`tenant_id`, `complaint_no`, `order_id`, `content`, `status`, `created_at_ms`) VALUES
('default', 'CP-seed-0001', '20260613002', '物流配送太慢，希望加快处理', 'PROCESSING', 1779235200000);

-- 知识库 FAQ 表（cw_knowledge）：JdbcKnowledgeBackend 演示表。种子三条与 Mock 一致（退货/发票/运费），LIKE 关键词检索。
CREATE TABLE IF NOT EXISTS `cw_knowledge` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识条目自增主键',
    `keyword`    VARCHAR(255) NOT NULL COMMENT '命中关键词（逗号分隔）',
    `title`      VARCHAR(255) NOT NULL COMMENT '条目标题',
    `content`    TEXT NOT NULL COMMENT '条目内容',
    `source`     VARCHAR(255) COMMENT '来源标注',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    UNIQUE KEY `uk_knowledge_title` (`tenant_id`, `title`),
    INDEX `idx_knowledge_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_knowledge` (`tenant_id`, `keyword`, `title`, `content`, `source`) VALUES
('default', '退货,退款,七天,无理由', '七天无理由退货政策', '支持七天无理由退货，商品需保持完好、不影响二次销售；定制类、生鲜类除外。', '《售后服务政策》第 3 条'),
('default', '发票,开票,报销', '发票开具规则', '支持开具电子普通发票与增值税专用发票，可在订单详情页自助申请，1-3 个工作日开具。', '《发票管理规则》第 1 条'),
('default', '运费,包邮,邮费', '运费说明', '单笔订单满 99 元包邮，偏远地区除外；退货运费由责任方承担。', '《运费说明》第 2 条');

-- 敏感词表（SensitiveWordFilter / cw_sensitive_word）：智能路由中控"一次拦截"词库。
-- 种子为脱敏占位词（非真实违禁词），覆盖 BLOCK/MASK/REVIEW 三种动作与多类目。
CREATE TABLE IF NOT EXISTS `cw_sensitive_word` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `word`           VARCHAR(128) NOT NULL COMMENT '敏感词原词面',
    `category`       VARCHAR(32) NOT NULL COMMENT '类目: POLITICS/PORN/ABUSE/COMPETITOR/CUSTOM',
    `action`         VARCHAR(16) NOT NULL COMMENT '处置动作: BLOCK/MASK/REVIEW',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_sensitive_word` (`tenant_id`, `word`),
    INDEX `idx_sensitive_word_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词表（一次拦截词库）';

INSERT IGNORE INTO `cw_sensitive_word` (`tenant_id`, `word`, `category`, `action`, `enabled`, `created_at_ms`, `updated_at_ms`) VALUES
('default', '测试敏感词A', 'CUSTOM', 'BLOCK', 1, 1779235200000, 1779235200000),
('default', '涉政占位', 'POLITICS', 'BLOCK', 1, 1779235200000, 1779235200000),
('default', '辱骂占位', 'ABUSE', 'BLOCK', 1, 1779235200000, 1779235200000),
('default', '竞品XX', 'COMPETITOR', 'MASK', 1, 1779235200000, 1779235200000),
('default', '复核占位', 'CUSTOM', 'REVIEW', 1, 1779235200000, 1779235200000);

-- 敏感词命中日志表（AsyncSensitiveWordHitSink / cw_sensitive_word_hit_log）：后台"命中看板"的数据源。
-- 仅当 customer-work.sensitive-word.hit-log.enabled=true 且 store-mode=jdbc 时写入。
-- snippet 存用户/模型原文片段（已截断），属敏感数据，是否留存由使用方按合规要求决定，故整块默认关闭。
CREATE TABLE IF NOT EXISTS `cw_sensitive_word_hit_log` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `direction`      VARCHAR(16) NOT NULL COMMENT '命中方向: INBOUND用户输入/OUTBOUND模型输出',
    `action`         VARCHAR(16) NOT NULL COMMENT '整体决策: BLOCK/MASK/REVIEW',
    `words`          VARCHAR(512) COMMENT '命中词面，逗号分隔',
    `categories`     VARCHAR(128) COMMENT '命中类目，逗号分隔已去重',
    `hit_count`      INT NOT NULL DEFAULT 0 COMMENT '命中词个数',
    `agent_name`     VARCHAR(128) COMMENT '智能体名',
    `session_id`     VARCHAR(128) COMMENT '会话ID',
    `user_id`        VARCHAR(128) COMMENT '用户ID',
    `snippet`        VARCHAR(512) COMMENT '原文片段（已按配置截断）',
    `created_at_ms`  BIGINT COMMENT '命中时间戳（毫秒）',
    KEY `idx_hit_created` (`created_at_ms`),
    KEY `idx_hit_action` (`action`),
    KEY `idx_hit_session` (`session_id`),
    INDEX `idx_sensitive_word_hit_log_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词命中日志（后台看板数据源）';

-- 限流规则表（RateLimitRuleProvider / cw_rate_limit_rule）：接入层限流的规则层，后台运营维护。
-- 刻意不给种子：空表 = 无规则命中 = 回退 yml 全局兜底参数，与规则化之前行为完全一致。
CREATE TABLE IF NOT EXISTS `cw_rate_limit_rule` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `rule_name`      VARCHAR(64) NOT NULL COMMENT '规则名（运营可读）',
    `path_prefix`    VARCHAR(128) NOT NULL COMMENT '匹配的请求路径前缀',
    `dimension`      VARCHAR(16) NOT NULL COMMENT '计数维度: API_KEY/IP/GLOBAL',
    `limit_count`    INT NOT NULL COMMENT '窗口内允许的最大请求数',
    `algorithm`      VARCHAR(32) NOT NULL COMMENT '算法: FIXED_WINDOW/SLIDING_WINDOW',
    `window_seconds` INT NOT NULL DEFAULT 60 COMMENT '时间窗（秒）',
    `priority`       INT NOT NULL DEFAULT 0 COMMENT '优先级，越小越先匹配（首匹配即止）',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_rate_limit_rule_name` (`tenant_id`, `rule_name`),
    KEY `idx_rate_limit_priority` (`priority`),
    INDEX `idx_rate_limit_rule_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限流规则表（接入层限流规则层）';

-- 坐席库表（MybatisSeatAgentStore / cw_seat_agent）：智能路由中控"工单智能分配"的候选坐席池。
-- skills 为逗号分隔技能标签串；seat_group 避开 SQL 保留字 group；种子为演示坐席（多技能/负载/在离线）。
CREATE TABLE IF NOT EXISTS `cw_seat_agent` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             VARCHAR(64) PRIMARY KEY COMMENT '坐席ID',
    `name`           VARCHAR(64) NOT NULL COMMENT '坐席名',
    `skills`         VARCHAR(512) COMMENT '技能标签（逗号分隔，如 refund,invoice）',
    `max_load`       INT NOT NULL DEFAULT 0 COMMENT '最大并发工单数',
    `current_load`   INT NOT NULL DEFAULT 0 COMMENT '当前在处理工单数',
    `online`         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否在线: 1在线/0离线',
    `seat_group`     VARCHAR(64) COMMENT '坐席分组',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    INDEX `idx_seat_online` (`online`),
    INDEX `idx_seat_agent_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席库（智能分配候选坐席池）';

INSERT IGNORE INTO `cw_seat_agent` (`tenant_id`, `id`, `name`, `skills`, `max_load`, `current_load`, `online`, `seat_group`, `created_at_ms`, `updated_at_ms`) VALUES
('default', 'SEAT-1001', '退款专员-小赵', 'refund,invoice', 5, 1, 1, 'aftersales', 1779235200000, 1779235200000),
('default', 'SEAT-1002', '物流专员-小钱', 'logistics', 5, 3, 1, 'logistics', 1779235200000, 1779235200000),
('default', 'SEAT-1003', '投诉专员-小孙', 'complaint,refund', 4, 0, 1, 'complaint', 1779235200000, 1779235200000),
('default', 'SEAT-1004', '综合坐席-小李', 'refund,logistics,complaint,invoice', 6, 5, 1, 'general', 1779235200000, 1779235200000),
('default', 'SEAT-1005', '离线坐席-小周', 'refund', 5, 0, 0, 'aftersales', 1779235200000, 1779235200000);

-- P1-03：智能分配增强字段已归属上文 cw_ticket。存量归并和幂等升级见
-- customer-work-handoff-authority-alter.sql / Flyway V17。

-- 数据字典（DictStore / cw_dict_type + cw_dict_item）：少量枚举型键值数据的统一落点，免于逐个建表。
-- 后台管理系统"字典管理"页直连维护这两张表（单一数据真源，照内容风控先例不做双写同步）。
CREATE TABLE IF NOT EXISTS `cw_dict_type` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `dict_type`      VARCHAR(64) NOT NULL COMMENT '字典类型编码（如 order_status）',
    `type_name`      VARCHAR(64) NOT NULL COMMENT '类型名称（展示用）',
    `remark`         VARCHAR(255) COMMENT '备注说明',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_dict_type` (`tenant_id`, `dict_type`),
    INDEX `idx_dict_type_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型表';

CREATE TABLE IF NOT EXISTS `cw_dict_item` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `dict_type`      VARCHAR(64) NOT NULL COMMENT '所属字典类型编码',
    `item_key`       VARCHAR(128) NOT NULL COMMENT '字典项键（业务值）',
    `item_label`     VARCHAR(128) NOT NULL COMMENT '字典项标签（展示文案）',
    `sort`           INT NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `remark`         VARCHAR(255) COMMENT '备注说明',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_dict_item` (`tenant_id`, `dict_type`, `item_key`),
    KEY `idx_dict_item_type` (`dict_type`),
    INDEX `idx_dict_item_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典项表';

-- 演示种子：订单状态（与 InMemoryDictStore 种子、后台订单页硬编码文案一致）。
INSERT IGNORE INTO `cw_dict_type` (`tenant_id`, `dict_type`, `type_name`, `remark`, `enabled`, `created_at_ms`, `updated_at_ms`) VALUES
('default', 'order_status', '订单状态', '用户订单状态筛选项（与后端返回的中文文案一致）', 1, 1779235200000, 1779235200000);

INSERT IGNORE INTO `cw_dict_item` (`tenant_id`, `dict_type`, `item_key`, `item_label`, `sort`, `enabled`, `remark`, `created_at_ms`, `updated_at_ms`) VALUES
('default', 'order_status', '待支付', '待支付', 1, 1, NULL, 1779235200000, 1779235200000),
('default', 'order_status', '已支付', '已支付', 2, 1, NULL, 1779235200000, 1779235200000),
('default', 'order_status', '待发货', '待发货', 3, 1, NULL, 1779235200000, 1779235200000),
('default', 'order_status', '已发货', '已发货', 4, 1, NULL, 1779235200000, 1779235200000),
('default', 'order_status', '已签收', '已签收', 5, 1, NULL, 1779235200000, 1779235200000),
('default', 'order_status', '已取消', '已取消', 6, 1, NULL, 1779235200000, 1779235200000),
('default', 'order_status', '已退款', '已退款', 7, 1, NULL, 1779235200000, 1779235200000);
-- 租户配额表（MybatisTenantQuotaStore / cw_tenant_quota）：B3 成本治理的硬上限。
-- 落在客服端库而非 admin 库：它要被运行时读取（拦在模型调用之前），照内容风控三表的先例
-- 由 starter 定义 Mapper、admin 复用同一套 Mapper 管理，避免跨库反查或两处各存一份。
-- 刻意不给种子：空表 = 无配额 = 不拦，与引入配额之前行为完全一致。
CREATE TABLE IF NOT EXISTS `cw_tenant_quota` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `period`         VARCHAR(16) NOT NULL COMMENT '周期: DAILY 日 / MONTHLY 月',
    `token_limit`    BIGINT NOT NULL DEFAULT 0 COMMENT 'token 上限，0=不限',
    `amount_limit`   DECIMAL(16,4) NOT NULL DEFAULT 0 COMMENT '金额上限（元），0=不限；实时链路只拦 token，金额走 T+1 账单告警',
    `exceed_action`  VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT '超额处置: BLOCK 拦截 / DEGRADE 降级备用模型 / WARN 仅告警',
    `warn_percent`   INT NOT NULL DEFAULT 80 COMMENT '预警阈值（用量百分比），达到即告警但不拦',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `remark`         VARCHAR(255) COMMENT '备注',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_tenant_quota` (`tenant_id`, `period`),
    INDEX `idx_tenant_quota_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='租户配额（每租户每周期一条）';

-- 长期记忆事实表（cw_long_term_memory）：三层记忆体系的 L2，跨会话语义召回底座（MybatisLongTermMemoryStore）。
-- 两个维度刻意分列：`tenant_id` 是 SaaS 租户（拦截器自动填充与过滤，见 TenantContext），
-- `scope_id` 是记忆分区键（由 sessionId 前缀解析，见 TenantResolver），二者不是一回事，合并会串记忆。
-- TEXT 列无法直接建唯一索引，去重靠 `scope_hash`（scope_id + fact 的 SHA-256）。
CREATE TABLE IF NOT EXISTS `cw_long_term_memory` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `scope_id`       VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '记忆分区键（TenantResolver 由 sessionId 解析）',
    `fact`           TEXT NOT NULL COMMENT '事实内容',
    `scope_hash`     VARCHAR(64) NOT NULL COMMENT 'scope_id + fact 的 SHA-256（去重键，TEXT 无法建唯一索引）',
    `created_at_ms`  BIGINT NOT NULL COMMENT '写入时间戳（毫秒）',
    UNIQUE KEY `uk_ltm_scope_fact` (`tenant_id`, `scope_hash`),
    INDEX `idx_ltm_scope` (`tenant_id`, `scope_id`, `id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='长期记忆事实（L2，跨会话语义召回）';

-- 事实日志表（cw_fact_log）：三层记忆体系的 L3，只追加、不可变、可审计的事实流水（MybatisFactLog）。
-- 取代按租户分文件的 JSONL 落盘（FileFactLog）；append-only 语义靠"只 INSERT 不 UPDATE/DELETE"保证，
-- 自增 `id` 即写入顺序（同毫秒内 ts 相同也不丢序）。
CREATE TABLE IF NOT EXISTS `cw_fact_log` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（即写入顺序）',
    `scope_id`       VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '记忆分区键（TenantResolver 由 sessionId 解析）',
    `fact`           TEXT NOT NULL COMMENT '事实内容',
    `ts`             BIGINT NOT NULL COMMENT '事实时间戳（毫秒）',
    `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    INDEX `idx_fact_log_scope` (`tenant_id`, `scope_id`, `id`),
    INDEX `idx_fact_log_ts` (`tenant_id`, `scope_id`, `ts`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='事实日志（L3，常规链路只追加，隐私治理可按主体擦除）';

-- 长期记忆主体同意记录：生产启用长期记忆时必须显式授权；撤回后停止写入/召回并清除 L2/L3。
CREATE TABLE IF NOT EXISTS `cw_memory_consent` (
    `tenant_id`        VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `subject_type`     VARCHAR(32) NOT NULL COMMENT '主体类型: USER/SESSION/SERVICE_ACCOUNT',
    `subject_id`       VARCHAR(128) NOT NULL COMMENT '租户内主体ID',
    `agent_id`         VARCHAR(128) NOT NULL COMMENT 'Agent稳定标识',
    `scope_id`         VARCHAR(68) NOT NULL COMMENT '四维主体键SHA-256分区',
    `status`           VARCHAR(16) NOT NULL COMMENT 'GRANTED/WITHDRAWN',
    `consent_version`  VARCHAR(64) NOT NULL COMMENT '用户同意的隐私条款版本',
    `granted_at_ms`    BIGINT NULL COMMENT '授权时间戳（毫秒）',
    `withdrawn_at_ms`  BIGINT NULL COMMENT '撤回时间戳（毫秒）',
    `updated_at_ms`    BIGINT NOT NULL COMMENT '最后更新时间戳（毫秒）',
    `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                         ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    UNIQUE KEY `uk_memory_consent_subject`
        (`tenant_id`, `subject_type`, `subject_id`, `agent_id`),
    UNIQUE KEY `uk_memory_consent_scope` (`tenant_id`, `scope_id`),
    INDEX `idx_memory_consent_status` (`tenant_id`, `status`, `updated_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='长期记忆主体同意记录';

-- Harness 分层记忆表（cw_harness_memory）：HarnessAgent 的 MEMORY.md 权威副本（MybatisHarnessMemoryStore）。
-- 框架只认 {workspace}/MEMORY.md 这个文件，故落盘不可避免；本表让"权威副本"落在 MySQL，
-- workspace 里的那份退化为构建实例时水合出来、可随时重建的工作副本（同 admin 侧 ai_agent_memory 的手法）。
-- scope_id 取 workspace 目录路径：starter 的 HarnessAgent 共用一个 workspace，记忆因而是 workspace 级；
-- 配成按租户分目录时同一套代码自然按租户分行，无需改动。
CREATE TABLE IF NOT EXISTS `cw_harness_memory` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `scope_id`       VARCHAR(512) NOT NULL COMMENT '记忆归属（workspace 目录路径）',
    `scope_hash`     VARCHAR(64) NOT NULL COMMENT 'scope_id 的 SHA-256（唯一键用，规避 512 字节索引长度限制）',
    `content`        MEDIUMTEXT NOT NULL COMMENT 'MEMORY.md 全文',
    `updated_at_ms`  BIGINT NOT NULL COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_harness_memory_scope` (`tenant_id`, `scope_hash`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='Harness 分层记忆（MEMORY.md 权威副本）';

-- 技能库表（cw_skill + cw_skill_file）：客服端从 MySQL 读技能包（MysqlSkillRepository）。
-- 与 admin 库的 ai_skill / ai_skill_file 结构对齐但各自独立：admin 管的是后台配置的智能体技能，
-- 这两张表是客服端运行时自己的技能库（跨库同步不在本批次范围内，由运维按需灌数据）。
-- 读出来后仍要物化成磁盘目录再交 FileSystemSkillRepository——框架只认文件，这是框架约束不是选型。
CREATE TABLE IF NOT EXISTS `cw_skill` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `skill_code`     VARCHAR(64) NOT NULL COMMENT '技能编码（= 落盘目录名）',
    `skill_name`     VARCHAR(64) NOT NULL COMMENT '技能名称',
    `content`        MEDIUMTEXT NOT NULL COMMENT 'SKILL.md 正文',
    `description`    VARCHAR(255) COMMENT '技能描述',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_cw_skill_code` (`tenant_id`, `skill_code`),
    INDEX `idx_cw_skill_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能库（SKILL.md 正文）';

-- 技能附属文件表：SKILL.md 里引用的 references/scripts/examples 等，不落盘技能就是残的。
CREATE TABLE IF NOT EXISTS `cw_skill_file` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `skill_id`       BIGINT NOT NULL COMMENT '所属技能（cw_skill.id）',
    `file_path`      VARCHAR(512) NOT NULL COMMENT '相对 SKILL.md 所在目录的路径，如 references/api.md',
    `file_size`      BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `content`        LONGBLOB COMMENT '文件内容（文本/二进制统一按字节存）',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    INDEX `idx_cw_skill_file_skill` (`skill_id`),
    INDEX `idx_cw_skill_file_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能附属文件';

-- 评测数据集内容快照：只插入、不更新；同租户/类型/内容只产生一个版本。
CREATE TABLE IF NOT EXISTS `cw_eval_dataset_version` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `version_id`     VARCHAR(64) NOT NULL COMMENT '数据集版本ID（应用生成UUID）',
    `eval_type`      VARCHAR(16) NOT NULL COMMENT 'INTENT/QUALITY',
    `content_hash`   VARCHAR(64) NOT NULL COMMENT '规范化用例JSON的SHA-256',
    `case_count`     INT NOT NULL COMMENT '快照用例数',
    `cases_json`     LONGTEXT NOT NULL COMMENT '本次实际执行的完整用例JSON',
    `created_at_ms`  BIGINT NOT NULL COMMENT '首次创建时间戳（毫秒）',
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_eval_dataset_content` (`tenant_id`, `eval_type`, `content_hash`),
    KEY `idx_eval_dataset_tenant_time` (`tenant_id`, `eval_type`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='评测数据集不可变版本';

-- 命名评测集版本：内容继续引用不可变快照，本表只承载版本名与一次性审核事实。
CREATE TABLE IF NOT EXISTS `cw_eval_dataset_release` (
    `tenant_id`          VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `release_id`         VARCHAR(64) NOT NULL COMMENT '命名版本ID（应用生成UUID）',
    `eval_type`          VARCHAR(16) NOT NULL COMMENT 'INTENT/QUALITY',
    `version_name`       VARCHAR(128) NOT NULL COMMENT '租户内、类型内唯一的人类可读版本名',
    `snapshot_version_id` VARCHAR(64) NOT NULL COMMENT '不可变内容快照 cw_eval_dataset_version.version_id',
    `content_hash`       VARCHAR(64) NOT NULL COMMENT '快照内容SHA-256，跨库绑定时用于校验漂移',
    `case_count`         INT NOT NULL COMMENT '版本包含的用例数',
    `status`             VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/APPROVED/REJECTED',
    `review_comment`     VARCHAR(500) DEFAULT NULL COMMENT '审核意见',
    `created_by`         BIGINT DEFAULT NULL COMMENT '创建人',
    `reviewed_by`        BIGINT DEFAULT NULL COMMENT '审核人',
    `created_at_ms`      BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `reviewed_at_ms`     BIGINT DEFAULT NULL COMMENT '审核时间戳（毫秒）',
    PRIMARY KEY (`release_id`),
    UNIQUE KEY `uk_eval_dataset_release_name` (`tenant_id`, `eval_type`, `version_name`),
    KEY `idx_eval_dataset_release_status` (`tenant_id`, `eval_type`, `status`, `created_at_ms`),
    KEY `idx_eval_dataset_release_snapshot` (`tenant_id`, `snapshot_version_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='评测数据集命名版本与审核';

-- 评测运行记录表（MybatisEvalRunStore / cw_eval_run）：每跑一次标准集落一条。
-- 评测的价值全在纵向对比上——"这版比上版好还是坏"；没有历史，每次运行都退化成孤立的一次性体检。
-- 只追加不更新：一次运行的结果是既成事实，改写它等于篡改后续所有对比的基线。
CREATE TABLE IF NOT EXISTS `cw_eval_run` (
    `tenant_id`            VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `run_id`               VARCHAR(64) PRIMARY KEY COMMENT '运行ID（应用赋值的UUID）',
    -- 写入顺序号：取基线("上一次运行")一律按它排，不按 created_at_ms。
    -- 意图评测是纯内存计算，连续两次能落在同一毫秒里，按毫秒时间戳定序会出现
    -- "第二次运行找不到基线"——同毫秒下 created_at_ms < now 不成立。CI 连跑必踩。
    `seq`                  BIGINT NOT NULL AUTO_INCREMENT UNIQUE COMMENT '写入顺序号（同毫秒也不丢序）',
    `eval_type`            VARCHAR(16) NOT NULL COMMENT '评测类型：INTENT 意图路由 / QUALITY 回复质量',
    `total`                INT NOT NULL DEFAULT 0 COMMENT '用例总数',
    `passed`               INT NOT NULL DEFAULT 0 COMMENT '通过数',
    -- 主/次指标一律归一化到 0-1：两类评测原始口径不同（准确率 vs 1-5 分），
    -- 不归一就没法共用同一段对比逻辑，每加一类评测都要再写一遍比较代码
    `primary_metric`       DOUBLE NOT NULL DEFAULT 0 COMMENT '主指标(0-1)：INTENT=准确率 / QUALITY=平均分/5',
    `secondary_metric`     DOUBLE NOT NULL DEFAULT 0 COMMENT '次指标(0-1)：INTENT=快车道覆盖率 / QUALITY=通过率',
    `failed_case_ids_json` TEXT COMMENT '失败用例ID的JSON数组（版本间回归识别的依据，不从明细里反解）',
    `failures_json`        TEXT COMMENT '失败明细的JSON数组（人读）',
    `metrics_json`         TEXT COMMENT '该类型完整原始指标的JSON字典（归一化不丢信息）',
    `trigger_source`       VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '触发来源：MANUAL/SCHEDULED/API',
    `dataset_size`         INT NOT NULL DEFAULT 0 COMMENT '评测集规模（用例增删后两次指标不可直接比）',
    `dataset_version_id`   VARCHAR(64) COMMENT '本次实际执行的数据集版本',
    `dataset_fingerprint`  VARCHAR(64) COMMENT '数据集内容SHA-256',
    `version_binding_json` TEXT COMMENT '模型/提示词/Agent/知识/工具/Judge/rubric版本绑定JSON',
    -- 效果归因的支点：指标掉了先看这一位变没变。变了就去比那两版提示词全文，
    -- 没变就别再对着提示词逐字找原因，该去查模型或数据
    `prompt_fingerprint`   VARCHAR(32) COMMENT '本次运行时生效的提示词指纹（cw_prompt_version.fingerprint）',
    `remark`               VARCHAR(500) COMMENT '备注（如"换 qwen-max 后重跑"）',
    `created_at_ms`        BIGINT NOT NULL COMMENT '运行时间戳（毫秒）',
    -- 取基线是 (eval_type, created_at_ms DESC) 上的取最值查询，联合索引直接命中
    INDEX `idx_eval_run_type_time` (`tenant_id`, `eval_type`, `created_at_ms`),
    INDEX `idx_eval_run_dataset_version` (`tenant_id`, `eval_type`, `dataset_version_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='评测运行记录（只追加，供版本对比）';

-- 评测用例表（MybatisEvalCaseStore / cw_eval_case）：让评测集能随 badcase 增长。
-- 此前两类用例只存在 jar 内的 JSON 里、运行时只读，"把 badcase 转成评测用例"这个动作无处落地——
-- 数据飞轮缺的正是这一环。classpath 种子仍保留（随代码走、经 code review，是基准线），
-- 本表只承载增量与修正：同 case_id 的记录会盖掉种子，enabled=0 等于屏蔽掉那条种子用例，
-- 两者都不需要改代码发版。
CREATE TABLE IF NOT EXISTS `cw_eval_case` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `eval_type`     VARCHAR(16) NOT NULL COMMENT '评测类型：INTENT 意图路由 / QUALITY 回复质量',
    `case_id`       VARCHAR(64) NOT NULL COMMENT '用例编号（同类型内唯一；与种子同号即覆盖种子）',
    `input`         VARCHAR(1024) NOT NULL COMMENT '用户输入',
    `expected`      VARCHAR(1024) COMMENT 'INTENT=期望意图（空=期望快车道不命中，交LLM）；QUALITY=期望要点',
    `category`      VARCHAR(64) COMMENT '归类标签',
    `source`        VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：SEED/BADCASE/MANUAL/IMPORT',
    `enabled`       TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否参与评测：0 可屏蔽同号种子用例',
    `origin_ref`    VARCHAR(64) COMMENT '溯源引用：来自 badcase 时记 badcase ID，便于回看原始会话',
    `created_at_ms` BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    -- 用例编号是人给的、可能被改，故主键用自增 id，业务唯一性靠这个联合唯一键
    UNIQUE KEY `uk_eval_case` (`tenant_id`, `eval_type`, `case_id`),
    INDEX `idx_eval_case_tenant` (`tenant_id`, `eval_type`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='评测用例（种子之外的增量与修正）';

-- badcase 待筛队列（MybatisBadcaseStore / cw_badcase）：数据飞轮缺的那一环。
-- 负反馈与质检失败早就写进 cw_fact_log 了，但那是 L3 审计流水——只追加、不可变、永不改写。
-- "这条处理了没有、转成了什么"是有状态的运营工作流，塞进审计流水会破坏它的根本约定。
-- 故两张表并存、各司其职：事实流水回答"当时发生了什么"，本表回答"我们拿它做了什么"。
--
-- user_input / agent_reply 在登记时从 cw_chat_message 回查补齐：只给运营一个 messageId，
-- 筛选界面就没法用——没人能凭一串 ID 判断该不该回流。
CREATE TABLE IF NOT EXISTS `cw_badcase` (
    `tenant_id`            VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`                   VARCHAR(64) PRIMARY KEY COMMENT 'badcase ID（应用赋值的UUID）',
    `source`               VARCHAR(24) NOT NULL COMMENT '来源：NEGATIVE_FEEDBACK 用户点踩 / QUALITY_FAILURE 质检不过',
    `session_id`           VARCHAR(128) COMMENT '所属会话',
    `message_id`           VARCHAR(64) COMMENT '被反馈的消息ID（质检来源为空，质检针对一批回复）',
    `user_input`           TEXT COMMENT '用户问了什么（从聊天留痕回查）',
    `agent_reply`          TEXT COMMENT 'AI答了什么（从聊天留痕回查）',
    `signal_hash`          CHAR(64) COMMENT '归一化用户问题SHA-256，供上线复发观测',
    `detail`               TEXT COMMENT '原始信号明细：点踩存用户留言，质检存得分与扣分项',
    `status`               VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待筛/RESOLVED 已处理/IGNORED 已忽略',
    -- 补知识是治本、加评测用例是防复发，两件事不互斥，故分两个字段而非做成互斥状态
    `adopted_knowledge_id` BIGINT COMMENT '已回流成的知识条目ID（cw_knowledge.id）',
    `adopted_eval_case_id` VARCHAR(64) COMMENT '已回流成的评测用例编号（cw_eval_case.case_id）',
    `handled_by`           VARCHAR(64) COMMENT '处理人',
    `handled_at_ms`        BIGINT COMMENT '处理时间戳（毫秒）',
    `ignore_reason`        VARCHAR(500) COMMENT '忽略原因（仅 IGNORED 时有值）',
    `created_at_ms`        BIGINT NOT NULL COMMENT '登记时间戳（毫秒）',
    -- 待筛队列按 (status, 时间倒序) 翻页，联合索引直接命中
    INDEX `idx_badcase_status` (`tenant_id`, `status`, `created_at_ms`),
    INDEX `idx_badcase_signal` (`tenant_id`, `signal_hash`, `created_at_ms`),
    INDEX `idx_badcase_session` (`session_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='badcase 待筛队列（回流知识库/评测用例）';

-- 语义缓存表（MybatisSemanticCacheStore / cw_semantic_cache）：问题向量相似即复用上次答案。
-- 客服问题重复率极高（"怎么退货"一天可能被问几百次），此前每次都完整打一遍模型。
--
-- **开启前必读**：无差别缓存客服回答会造成数据泄露——两个用户都问"我的订单到哪了"，
-- 语义高度相似但正确答案完全不同。故只缓存与个人上下文无关的通用问答，
-- 判定收口在 SemanticCacheService#cacheable：意图白名单（默认仅 consult）+ 个人标识过滤
-- （问题或答案含 6 位以上连续数字即跳过）+ 双层隔离。默认整体关闭。
--
-- MySQL 8.0 无原生向量索引，相似度在应用层逐条算（与 admin 侧知识检索同一手法），
-- 因此必须有容量上限与候选数上限，否则查缓存会比调模型还慢。
CREATE TABLE IF NOT EXISTS `cw_semantic_cache` (
    `tenant_id`       VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离，拦截器自动改写）',
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    -- 与 tenant_id 是两回事：这是缓存分区键（TenantResolver 由 sessionId 前缀解析），
    -- 同 cw_fact_log 的 scope_id；容量淘汰与失效清空都按它进行
    `scope_id`        VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '缓存分区键',
    `config_generation` VARCHAR(64) NOT NULL DEFAULT 'bootstrap' COMMENT '写入时运行配置 contentHash；bootstrap 表示尚未接入热配置',
    `intent`          VARCHAR(32) NOT NULL COMMENT '意图分类，命中时先按它缩小候选集（关键剪枝）',
    `question`        VARCHAR(512) NOT NULL COMMENT '原始问题（人读，排查"为什么这条命中了"要看）',
    `question_vector` MEDIUMTEXT NOT NULL COMMENT '问题向量，逗号分隔浮点数',
    `answer`          TEXT NOT NULL COMMENT '当时的回答',
    `hit_count`       BIGINT NOT NULL DEFAULT 0 COMMENT '命中次数（容量淘汰时保留高频条目）',
    `created_at_ms`   BIGINT NOT NULL COMMENT '写入时间戳（毫秒），TTL 以此为准',
    `last_hit_at_ms`  BIGINT NOT NULL COMMENT '最近命中时间戳（毫秒），LRU 淘汰以此为准',
    -- 候选集查询是 (scope_id, intent, last_hit_at_ms DESC) 上的限额扫描，联合索引直接命中
    INDEX `idx_semcache_lookup` (`tenant_id`, `config_generation`, `scope_id`, `intent`, `last_hit_at_ms`),
    INDEX `idx_semcache_created` (`tenant_id`, `config_generation`, `scope_id`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='语义缓存（仅通用问答，默认关闭）';

-- 提示词版本表（MybatisPromptVersionStore / cw_prompt_version）：效果归因的底座。
-- B4 的 ai_config_version 记的是"这次发布下发了什么"，本表记的是"运行时实际生效的是什么"——
-- 灰度只发给部分租户、实例还没收到推送、有人直接改了 Nacos 没走发布流程，都会让两者不一致，
-- 而能跟评测指标对上号的只有后者。
--
-- 主键取内容指纹而非外部版本号：提示词下发的是内容、没有随行版本号，要求发布方额外传一个，
-- 等于把"版本对不对得上"寄托在每次都记得传且传得对。内容变了指纹必变，跨环境也稳定。
CREATE TABLE IF NOT EXISTS `cw_prompt_version` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `fingerprint`    VARCHAR(32) PRIMARY KEY COMMENT '内容指纹（SHA-256 十六进制前16位）',
    `content`        MEDIUMTEXT NOT NULL COMMENT '提示词全文（归因时比对两版差异）',
    `length`         INT NOT NULL DEFAULT 0 COMMENT '全文字符数（列表页展示，避免每行拖全文）',
    -- 同一版会被反复观测到（重启、多副本），写入用 INSERT IGNORE 保留最早那次，
    -- 那才是"这版什么时候上线的"
    `captured_at_ms` BIGINT NOT NULL COMMENT '首次观测到该版本的时间戳（毫秒）',
    `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    INDEX `idx_prompt_version_time` (`tenant_id`, `captured_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='提示词版本（运行时实际生效的那份）';

-- 会话级满意度表（MybatisCsatStore / cw_csat_survey）：客服行业最标准的运营指标，此前完全拿不到。
-- 与消息级点赞/点踩（cw_message_feedback）是两个不同指标、不能互相替代：
-- 点踩衡量"某一句答得好不好"，CSAT 衡量"这次服务整体解决了没有"。
-- 一次会话可能每句都答得像样但问题始终没解决——那会拿到一堆 UP 和一个 2 分。
--
-- 邀请与评分分两个时间戳记：只记评分就算不出回收率，而回收率低时那个漂亮的 CSAT
-- 其实只代表愿意评价的一小撮人（特别满意与特别不满的两头），沉默的大多数不在样本里。
CREATE TABLE IF NOT EXISTS `cw_csat_survey` (
    `tenant_id`       VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `session_id`      VARCHAR(128) PRIMARY KEY COMMENT '会话ID（自然主键：一次会话只该有一次整体评价）',
    `scope_id`        VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '运营统计分区键 = 租户码（OpsScopeResolver 取当前租户上下文）',
    `score`           TINYINT COMMENT '评分 1-5；NULL 表示已邀请未评价（回收率的分母靠它区分）',
    `comment`         TEXT COMMENT '文字说明',
    `invited_at_ms`   BIGINT NOT NULL COMMENT '发出邀请时间戳（毫秒）——统计窗口以它为准',
    `submitted_at_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '提交评分时间戳（毫秒）；未评价为 0',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    -- 统计按 (scope_id, invited_at_ms) 的窗口扫描：分子分母必须同一口径，
    -- 按提交时间筛会把"这周邀请、下周才评"的算进下周，两头都不对
    INDEX `idx_csat_window` (`tenant_id`, `scope_id`, `invited_at_ms`),
    INDEX `idx_csat_score` (`tenant_id`, `score`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='会话级满意度调查（CSAT）';

-- 知识盲区表（MybatisKnowledgeGapStore / cw_knowledge_gap）：哪些问题反复查不到知识。
-- 这份数据本来唾手可得——检索未命中时记一笔就行——但此前没人记，于是"该补哪些知识"全靠拍脑袋，
-- 而拍出来的往往是运营自己关心的，不是用户实际在问的。
--
-- 这是**计数表而非流水表**：用户要的是"哪些问题反复查不到"，只出现过一次的问法没有补知识的价值，
-- 而未命中的绝对量在客服场景很大，逐条落库既贵又淹没重点。
CREATE TABLE IF NOT EXISTS `cw_knowledge_gap` (
    `tenant_id`         VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    -- 问题可能很长，直接做唯一键会撞上索引长度限制，故用哈希（同 cw_harness_memory 的 scope_hash 手法）
    `question_hash`     VARCHAR(64) NOT NULL COMMENT '问题原文的 SHA-256',
    `question`          VARCHAR(512) NOT NULL COMMENT '问题原文（截断保存）——运营要看的就是这个',
    `scope_id`          VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '运营统计分区键 = 租户码（OpsScopeResolver 取当前租户上下文）',
    `miss_count`        BIGINT NOT NULL DEFAULT 1 COMMENT '累计未命中次数：排行依据，越大越该优先补',
    `first_seen_at_ms`  BIGINT NOT NULL COMMENT '首次出现时间戳（毫秒）——这个问题何时开始查不到',
    `last_seen_at_ms`   BIGINT NOT NULL COMMENT '最近出现时间戳（毫秒）',
    `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    UNIQUE KEY `uk_knowledge_gap` (`tenant_id`, `scope_id`, `question_hash`),
    -- 排行查询是 (scope_id, miss_count DESC) 的限额扫描
    INDEX `idx_knowledge_gap_rank` (`tenant_id`, `scope_id`, `miss_count`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='知识盲区（反复检索不到的问题，计数表）';

-- 死信队列表（MybatisDeadLetterStore / cw_dead_letter）：让"失败了就记条 error"变成"失败了会自己补回来"。
-- 此前工具调用失败、主动通知发送失败都只落一行日志，业务量小时看不出来，量一上来就是实打实的丢单——
-- 用户以为退款申请提交了，下游其实根本没收到，而没有任何机制会发现这件事。
--
-- 重试次数耗尽后转 ABANDONED 而**不删除**：静默丢弃正是现在的问题所在，留档才能让运营捞出来手工补。
CREATE TABLE IF NOT EXISTS `cw_dead_letter` (
    `tenant_id`        VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`               VARCHAR(64) PRIMARY KEY COMMENT '死信ID（应用赋值的UUID）',
    `type`             VARCHAR(64) NOT NULL COMMENT '死信类型：决定由哪个 DeadLetterHandler 重投',
    -- 载荷必须自包含：重投发生在几分钟甚至几小时后，原始调用栈早就不在了
    `payload`          TEXT NOT NULL COMMENT '重投所需的完整载荷（JSON）',
    `biz_key`          VARCHAR(128) COMMENT '关联业务标识（订单号/会话号），供运营检索',
    `status`           VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待重投/SUCCEEDED 已成功/ABANDONED 已放弃',
    `attempts`         INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `last_error`       TEXT COMMENT '最近一次失败原因',
    -- 指数退避 base*2^attempts：下游多半是被打挂了或正在重启，
    -- 固定短间隔的密集重试只会把它按在地上，变成自己给自己制造的雪崩
    `next_retry_at_ms` BIGINT NOT NULL COMMENT '下次重投时刻（毫秒）',
    `lease_owner`      VARCHAR(128) COMMENT '当前租约持有实例',
    `lease_until_ms`   BIGINT NOT NULL DEFAULT 0 COMMENT '租约到期时间',
    `created_at_ms`    BIGINT NOT NULL COMMENT '失败发生时刻（毫秒）',
    `finished_at_ms`   BIGINT NOT NULL DEFAULT 0 COMMENT '终态时刻（成功或放弃）；未终结为 0',
    -- 巡检取的是 (status=PENDING, next_retry_at_ms <= now) 的限额扫描
    INDEX `idx_dead_letter_due` (`tenant_id`, `status`, `next_retry_at_ms`),
    INDEX `idx_dead_letter_lease` (`tenant_id`, `status`, `lease_until_ms`),
    INDEX `idx_dead_letter_biz` (`tenant_id`, `biz_key`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='死信队列（失败操作的兜底重投）';

-- 同库事务 Outbox：业务状态、审计事件与待投递消息原子提交。
CREATE TABLE IF NOT EXISTS `cw_outbox_message` (
    `tenant_id`          VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`                 VARCHAR(64) PRIMARY KEY COMMENT '消息ID，也是下游幂等键',
    `type`               VARCHAR(64) NOT NULL COMMENT 'Handler 类型',
    `aggregate_id`       VARCHAR(128) NOT NULL COMMENT '聚合根业务标识',
    `payload`            MEDIUMTEXT NOT NULL COMMENT '自包含 JSON 载荷',
    `status`             VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCEEDED/ABANDONED',
    `attempts`           INT NOT NULL DEFAULT 0 COMMENT '投递失败次数',
    `next_attempt_at_ms` BIGINT NOT NULL COMMENT '下次投递时间',
    `lease_owner`        VARCHAR(128) COMMENT '当前租约持有实例',
    `lease_until_ms`     BIGINT NOT NULL DEFAULT 0 COMMENT '租约到期时间',
    `last_error`         TEXT COMMENT '最近一次投递错误',
    `created_at_ms`      BIGINT NOT NULL COMMENT '创建时间',
    `finished_at_ms`     BIGINT NOT NULL DEFAULT 0 COMMENT '终态时间',
    INDEX `idx_outbox_due` (`tenant_id`, `status`, `next_attempt_at_ms`),
    INDEX `idx_outbox_lease` (`tenant_id`, `status`, `lease_until_ms`),
    INDEX `idx_outbox_aggregate` (`tenant_id`, `aggregate_id`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='同库事务 Outbox';

-- 主体配额等级表（cw_subject_quota_level）：每个用户/匿名IP/API Key 在滚动窗口内的额度定义。
-- 与 cw_tenant_quota 刻意分表：那张是自然日/月对齐、要跟账单对得上的计费上限，
-- 这张是最近 N 秒滚动、与账单无关的防滥用闸门。周期语义与判定时机都不同，合表只会互相牵制。
CREATE TABLE IF NOT EXISTS `cw_subject_quota_level` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `level_code`     VARCHAR(64) NOT NULL COMMENT '等级编码，如 free/vip/anonymous',
    `level_name`     VARCHAR(128) NOT NULL COMMENT '等级名称（运营可读）',
    `subject_type`   VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT '适用主体: USER 登录用户 / IP 匿名 / API_KEY 接入方',
    `window_seconds` INT NOT NULL DEFAULT 1800 COMMENT '滚动窗口长度（秒），1800=30分钟',
    `token_limit`    BIGINT NOT NULL DEFAULT 0 COMMENT '窗口内 token 上限，0=不限',
    `request_limit`  INT NOT NULL DEFAULT 0 COMMENT '窗口内请求次数上限，0=不限',
    `exceed_action`  VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT '超限处置: BLOCK 拦截 / WARN 仅记录',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `remark`         VARCHAR(255) COMMENT '备注',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_squota_level` (`tenant_id`, `level_code`),
    INDEX `idx_squota_level_tenant` (`tenant_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='主体配额等级（每租户每档一条）';

-- 主体配额超限命中记录（cw_subject_quota_hit）：只在真的触顶那一刻写一条，正常流量零写入。
-- 后台"谁在刷"看板的数据源；实时余额刻意不落库（那在计数器里，跨进程读不到也没必要读）。
CREATE TABLE IF NOT EXISTS `cw_subject_quota_hit` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `subject_type`   VARCHAR(32) NOT NULL COMMENT '主体类型: USER/IP/API_KEY',
    `subject_id`     VARCHAR(128) NOT NULL COMMENT '主体标识（API Key 已做 SHA-256 指纹，不含明文）',
    `level_code`     VARCHAR(64) COMMENT '判定所依据的等级',
    `limit_kind`     VARCHAR(16) NOT NULL COMMENT '触顶维度: TOKEN/REQUEST',
    `used`           BIGINT NOT NULL DEFAULT 0 COMMENT '触顶时已用量',
    `limit_value`    BIGINT NOT NULL DEFAULT 0 COMMENT '触顶时的上限',
    `window_seconds` INT NOT NULL DEFAULT 0 COMMENT '滚动窗口长度（秒）',
    `action`         VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT '当时处置: BLOCK 真拦了 / WARN 只记录',
    `resource`       VARCHAR(255) COMMENT '触发位置（HTTP 路径或 ws:chat）',
    `created_at_ms`  BIGINT NOT NULL COMMENT '命中时刻（毫秒）',
    INDEX `idx_squota_hit_tenant_time` (`tenant_id`, `created_at_ms`),
    INDEX `idx_squota_hit_subject` (`tenant_id`, `subject_type`, `subject_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='主体配额超限命中记录';

-- 出厂五档种子（仅默认租户）。功能默认关闭，种子不改变任何现有行为。
-- free/anonymous/api-key 三档必须与 SubjectQuotaProperties 的内置档数值一致，两处不能漂移。
INSERT INTO `cw_subject_quota_level`
    (`tenant_id`, `level_code`, `level_name`, `subject_type`, `window_seconds`,
     `token_limit`, `request_limit`, `exceed_action`, `enabled`, `remark`,
     `created_at_ms`, `updated_at_ms`)
VALUES
    ('default', 'free',      '免费用户', 'USER',    1800,   50000,  100, 'BLOCK', 1, '注册用户默认档', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'vip',       'VIP用户',  'USER',    1800,  200000,  300, 'BLOCK', 1, '付费用户',       UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'svip',      'SVIP用户', 'USER',    1800, 1000000, 1000, 'BLOCK', 1, '高级付费用户',   UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'anonymous', '匿名访客', 'IP',      1800,   10000,   20, 'BLOCK', 1, '未登录，按来源IP计', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'api-key',   '接入方',   'API_KEY', 3600, 1000000, 2000, 'BLOCK', 1, '服务端接入，按Key指纹计', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    -- 后台管理系统登录用户（sys_user）：内部员工跑调试/VibeCoding，负载重、频次低，故窗口 1 小时、额度放宽
    ('default', 'admin-default', '后台用户', 'ADMIN_USER', 3600,  2000000,  200, 'BLOCK', 1, '后台登录用户默认档', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'admin-power',   '后台高配', 'ADMIN_USER', 3600, 10000000, 1000, 'BLOCK', 1, '给需要跑大批量调试的账号单独提档', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);
