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
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `event_type`  VARCHAR(64) NOT NULL COMMENT '事件类型: tool-call / final-answer / error',
    `agent_name`  VARCHAR(128) DEFAULT '' COMMENT 'Agent 名称',
    `event_data`  TEXT COMMENT '结构化事件字段 JSON',
    `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    INDEX `idx_audit_type` (`event_type`),
    INDEX `idx_audit_created` (`created_at`),
    INDEX `idx_audit_agent` (`agent_name`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 人工审批工单表（JdbcApprovalStore 结构化存储，human-approval.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcApprovalStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。退款等资金动作的审批单持久化于此，
--       保证应用重启 / 多实例部署下审批单不丢失。

CREATE TABLE IF NOT EXISTS `cw_approval` (
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
    INDEX `idx_approval_created` (`created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 多轮槽位收集进度表（JdbcSlotFillingStore 结构化存储，slot-filling.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcSlotFillingStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。保证多轮信息收集（如退款表单：订单号→原因）
--       中途重启可续填，用户无需从头重答。

CREATE TABLE IF NOT EXISTS `cw_slot_filling_progress` (
    `progress_key`    VARCHAR(191) PRIMARY KEY COMMENT '收集进度键：sessionId:formName',
    `asking`          VARCHAR(64) COMMENT '当前追问的槽位名',
    `collected_json`  TEXT COMMENT '已收集槽位值（JSON）'
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 对话阶段状态机表（JdbcDialogStageStore 结构化存储，dialog.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcDialogStageStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。多实例部署下跨实例共享同一份会话阶段，
--       避免负载均衡到不同实例导致阶段状态"归零"回 GREETING。

CREATE TABLE IF NOT EXISTS `cw_dialog_stage` (
    `session_id`  VARCHAR(191) PRIMARY KEY COMMENT '会话 ID',
    `stage`       VARCHAR(24) NOT NULL COMMENT '当前对话阶段：GREETING/COLLECTING/PROCESSING/CONFIRMING/ESCALATED'
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 人机切换工单表（JdbcHandoffStore 结构化存储，human-handoff.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcHandoffStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。取代此前 transferToHuman "只打日志 + 生成随机字符串"
--       的空实现——AI 转出生成 PENDING 工单，坐席 claim 接单（CLAIMED），处理完毕 resolve
--       结案（RESOLVED，会话可回收给 AI 续接）。多实例部署下坐席在实例 A 接单、
--       坐席工作台轮询落到实例 B 也应查到最新状态。

CREATE TABLE IF NOT EXISTS `cw_handoff_ticket` (
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
    INDEX `idx_handoff_created` (`created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =============================================================================
-- 消息级用户反馈表（JdbcFeedbackStore 结构化存储，feedback.store-mode=jdbc 时启用）
-- =============================================================================
-- 说明：由 JdbcFeedbackStore 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境。用户对 /chat 回复的点赞/点踩，DOWN 类型自动沉淀
--       到 FactLog 供离线复盘，是数据飞轮除系统主动质检外的另一条用户主动输入通道。
--       同一 message_id 重复提交按最新一次覆盖（用户改变主意允许更正）。

CREATE TABLE IF NOT EXISTS `cw_message_feedback` (
    `message_id`     VARCHAR(64) PRIMARY KEY COMMENT '被反馈的消息ID',
    `session_id`     VARCHAR(128) COMMENT '所属会话',
    `type`           VARCHAR(8) NOT NULL COMMENT 'UP/DOWN',
    `comment`        TEXT COMMENT '文字说明',
    `created_at_ms`  BIGINT NOT NULL COMMENT '提交时间戳（毫秒，重复提交取最新）',
    INDEX `idx_feedback_session` (`session_id`),
    INDEX `idx_feedback_type` (`type`)
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
    `id`             VARCHAR(64) PRIMARY KEY COMMENT '用户ID',
    `username`       VARCHAR(64) NOT NULL COMMENT '用户名',
    `password_hash`  VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname`       VARCHAR(64) COMMENT '昵称',
    `phone`          VARCHAR(32) COMMENT '手机号',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `avatar_url`     VARCHAR(255) COMMENT '头像访问URL（相对路径，可为空）',
    UNIQUE KEY `uk_user_username` (`username`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 客服工单主表（cw_ticket）：完整生命周期状态机（AI_SERVING→WAITING_AGENT→PROCESSING→...→CLOSED）。
CREATE TABLE IF NOT EXISTS `cw_ticket` (
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
    INDEX `idx_ticket_assignee` (`assignee`, `status`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 工单事件轨迹表（cw_ticket_event）：每次状态流转一条，不可变审计。
CREATE TABLE IF NOT EXISTS `cw_ticket_event` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件自增主键',
    `ticket_id`      VARCHAR(64) NOT NULL COMMENT '所属工单号',
    `event_type`     VARCHAR(32) NOT NULL COMMENT '事件类型',
    `from_status`    VARCHAR(32) COMMENT '流转前状态',
    `to_status`      VARCHAR(32) COMMENT '流转后状态',
    `actor_type`     VARCHAR(16) NOT NULL COMMENT '动作发起方类型',
    `actor_id`       VARCHAR(64) COMMENT '动作发起方标识',
    `note`           VARCHAR(500) COMMENT '备注',
    `created_at_ms`  BIGINT NOT NULL COMMENT '事件时间戳（毫秒）',
    INDEX `idx_ticket_event` (`ticket_id`, `id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 聊天消息留痕表（cw_chat_message）：会话/工单双维度，自增主键用于游标翻页。
CREATE TABLE IF NOT EXISTS `cw_chat_message` (
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
    INDEX `idx_chat_ticket` (`ticket_id`, `id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 智能体调用主记录表（cw_agent_call_log）：每次调用一行，含分段耗时冗余汇总（MybatisAgentCallLogStore）。
CREATE TABLE IF NOT EXISTS `cw_agent_call_log` (
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
    `success`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '整次调用是否成功',
    `error_msg`      VARCHAR(1024) COMMENT '失败原因',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    INDEX `idx_call_request` (`request_id`),
    INDEX `idx_call_username` (`username`),
    INDEX `idx_call_agent_code` (`agent_code`),
    INDEX `idx_call_session` (`session_id`),
    INDEX `idx_call_start` (`start_time`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 智能体调用分段明细表（cw_agent_call_segment）：一次调用的每段耗时一行（MybatisAgentCallLogStore）。
CREATE TABLE IF NOT EXISTS `cw_agent_call_segment` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `call_log_id`    BIGINT NOT NULL COMMENT '所属主记录ID',
    `seq`            INT NOT NULL COMMENT '调用内分段序号（从1起）',
    `kind`           VARCHAR(16) NOT NULL COMMENT '分段类别 MODEL/TOOL/MCP/SKILL',
    `name`           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '分段名称（模型名/工具名）',
    `start_time`     BIGINT NOT NULL COMMENT '分段开始时间戳（毫秒）',
    `duration_ms`    BIGINT NOT NULL DEFAULT 0 COMMENT '分段耗时（毫秒）',
    `input_tokens`   BIGINT DEFAULT NULL COMMENT '输入token（仅MODEL段，缺失为NULL）',
    `output_tokens`  BIGINT DEFAULT NULL COMMENT '输出token（仅MODEL段，缺失为NULL）',
    `success`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '分段是否成功',
    `error_msg`      VARCHAR(1024) COMMENT '失败原因',
    INDEX `idx_segment_call_log` (`call_log_id`, `seq`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 对话附件表（cw_chat_attachment）：上传附件落盘 + 落库，解析文本可追溯（MybatisAttachmentStore）。
CREATE TABLE IF NOT EXISTS `cw_chat_attachment` (
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
    INDEX `idx_cw_attachment_created` (`created_at`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 商品表（cw_product）：JdbcProductBackend 演示表。
CREATE TABLE IF NOT EXISTS `cw_product` (
    `product_id`   VARCHAR(32) PRIMARY KEY COMMENT '商品ID',
    `name`         VARCHAR(128) NOT NULL COMMENT '商品名称',
    `category`     VARCHAR(64) COMMENT '品类',
    `price`        DECIMAL(10,2) NOT NULL COMMENT '价格',
    `stock`        INT NOT NULL DEFAULT 0 COMMENT '库存',
    `description`  VARCHAR(500) COMMENT '商品描述',
    `promotion`    VARCHAR(255) COMMENT '优惠活动',
    `status`       VARCHAR(16) NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE/OFF_SALE',
    INDEX `idx_product_category` (`category`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_product` (`product_id`, `name`, `category`, `price`, `stock`, `description`, `promotion`, `status`) VALUES
('P001', '旗舰款无线降噪耳机', '耳机', 299.00, 100, '旗舰款无线降噪耳机，蓝牙 5.3，续航 30 小时，支持多点连接，颜色 黑/白，质保 1 年', '满 300 减 50；可叠加新人券 20 元；下单送收纳包', 'ON_SALE'),
('P002', '运动防汗蓝牙耳机', '耳机', 199.00, 50, '运动防汗蓝牙耳机，IPX5 级防水，佩戴稳固，适合健身运动', '限时直降 30 元，晒单再返 10 元', 'ON_SALE'),
('P003', '商务降噪头戴耳机', '耳机', 599.00, 0, '商务降噪头戴耳机，主动降噪，麦克风通话清晰，续航 40 小时', '', 'ON_SALE');

-- 订单表（cw_order）：JdbcOrderBackend 演示表。种子含 Mock 示例订单，状态/金额/下单日期与 Mock 一致。
CREATE TABLE IF NOT EXISTS `cw_order` (
    `order_id`         VARCHAR(32) PRIMARY KEY COMMENT '订单号',
    `user_id`          VARCHAR(64) NOT NULL COMMENT '下单用户',
    `product_id`       VARCHAR(32) NOT NULL COMMENT '商品ID',
    `product_name`     VARCHAR(128) COMMENT '商品名称',
    `amount`           DECIMAL(10,2) NOT NULL COMMENT '订单金额',
    `status`           VARCHAR(32) NOT NULL COMMENT '订单状态',
    `receiver_addr`    VARCHAR(255) COMMENT '收货地址',
    `logistics_trace`  TEXT COMMENT '物流轨迹',
    `created_at_ms`    BIGINT NOT NULL COMMENT '下单时间戳（毫秒）',
    INDEX `idx_order_user` (`user_id`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_order` (`order_id`, `user_id`, `product_id`, `product_name`, `amount`, `status`, `receiver_addr`, `logistics_trace`, `created_at_ms`) VALUES
('20260613001', 'U-demo-1', 'P001', '旗舰款无线降噪耳机', 299.00, '已发货', '北京市朝阳区建国路 88 号', '[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。', 1781049600000),
('20260613002', 'U-demo-1', 'P003', '商务降噪头戴耳机', 1599.00, '已签收', '上海市浦东新区世纪大道 100 号', '[5-18 已揽收]→[5-19 运输中]→[5-20 已签收]。', 1779235200000),
('20260613003', 'U-demo-2', 'P002', '运动防汗蓝牙耳机', 199.00, '待发货', '广州市天河区体育西路 1 号', '[6-13 已下单，仓库备货中]', 1781308800000),
('20260613004', 'U-demo-2', 'P001', '旗舰款无线降噪耳机', 299.00, '已退款', '深圳市南山区科技园路 5 号', '[6-08 已揽收]→[6-09 用户取消]→[6-10 已退款]', 1780876800000);

-- 售后工单表（cw_refund）：JdbcAfterSalesBackend 演示表。退款/退货/换货共表，submitRefund 只落 PENDING 待人工复核（资金红线）。
CREATE TABLE IF NOT EXISTS `cw_refund` (
    `refund_no`      VARCHAR(64) PRIMARY KEY COMMENT '售后工单号',
    `order_id`       VARCHAR(32) NOT NULL COMMENT '关联订单号',
    `type`           VARCHAR(16) NOT NULL COMMENT '类型：REFUND/RETURN/EXCHANGE',
    `status`         VARCHAR(16) NOT NULL COMMENT '状态：PENDING/APPROVED/DENIED',
    `amount`         DECIMAL(10,2) COMMENT '退款金额（退货/换货可空）',
    `reason`         VARCHAR(500) COMMENT '诉求原因',
    `new_spec`       VARCHAR(128) COMMENT '换货目标规格',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_refund_order` (`order_id`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_refund` (`refund_no`, `order_id`, `type`, `status`, `amount`, `reason`, `new_spec`, `created_at_ms`) VALUES
('RF-seed-20260613004', '20260613004', 'REFUND', 'APPROVED', 299.00, '七天无理由退款', NULL, 1781049600000);

-- 发票申请表（cw_invoice_request）：JdbcAfterSalesBackend 演示表。requestInvoice 真实落库。
CREATE TABLE IF NOT EXISTS `cw_invoice_request` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '发票申请自增主键',
    `order_id`       VARCHAR(32) NOT NULL COMMENT '关联订单号',
    `invoice_title`  VARCHAR(255) NOT NULL COMMENT '发票抬头',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/ISSUED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_invoice_order` (`order_id`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 会员表（cw_member）：JdbcMemberBackend 演示表。种子 U-demo-1 与 Mock 数据一致（黄金会员/积分 1280）。
CREATE TABLE IF NOT EXISTS `cw_member` (
    `member_id`        VARCHAR(64) PRIMARY KEY COMMENT '会员ID（对应用户ID）',
    `level`            VARCHAR(32) NOT NULL COMMENT '会员等级',
    `points`           INT NOT NULL DEFAULT 0 COMMENT '当前积分',
    `points_expiring`  INT NOT NULL DEFAULT 0 COMMENT '本月底到期积分',
    `benefits`         VARCHAR(255) COMMENT '等级权益',
    `next_level`       VARCHAR(32) COMMENT '下一等级',
    `upgrade_gap`      DECIMAL(10,2) DEFAULT 0 COMMENT '升级所需再消费金额',
    `phone`            VARCHAR(32) COMMENT '注册手机号'
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_member` (`member_id`, `level`, `points`, `points_expiring`, `benefits`, `next_level`, `upgrade_gap`, `phone`) VALUES
('U-demo-1', '黄金会员', 1280, 200, '免运费、专属客服、生日双倍积分', '铂金', 500.00, '138****0001'),
('U-demo-2', '白银会员', 320, 0, '满额包邮、积分商城兑换', '黄金', 800.00, '139****0002');

-- 会员账户问题处理日志表（cw_member_account_log）：JdbcMemberBackend 演示表。resolveAccountIssue 落一条处理日志。
CREATE TABLE IF NOT EXISTS `cw_member_account_log` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '处理日志自增主键',
    `issue`          VARCHAR(255) NOT NULL COMMENT '账户问题描述',
    `handling`       VARCHAR(500) COMMENT '处置话术',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_account_log_created` (`created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 投诉工单表（cw_complaint）：JdbcComplaintBackend 演示表。种子 CP-seed-0001 支撑 queryComplaint 演示。
CREATE TABLE IF NOT EXISTS `cw_complaint` (
    `complaint_no`   VARCHAR(64) PRIMARY KEY COMMENT '投诉工单号',
    `order_id`       VARCHAR(32) COMMENT '关联订单号（可空）',
    `content`        TEXT COMMENT '投诉内容',
    `status`         VARCHAR(16) NOT NULL COMMENT '状态：PROCESSING/RESOLVED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_complaint_order` (`order_id`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_complaint` (`complaint_no`, `order_id`, `content`, `status`, `created_at_ms`) VALUES
('CP-seed-0001', '20260613002', '物流配送太慢，希望加快处理', 'PROCESSING', 1779235200000);

-- 知识库 FAQ 表（cw_knowledge）：JdbcKnowledgeBackend 演示表。种子三条与 Mock 一致（退货/发票/运费），LIKE 关键词检索。
CREATE TABLE IF NOT EXISTS `cw_knowledge` (
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识条目自增主键',
    `keyword`    VARCHAR(255) NOT NULL COMMENT '命中关键词（逗号分隔）',
    `title`      VARCHAR(255) NOT NULL COMMENT '条目标题',
    `content`    TEXT NOT NULL COMMENT '条目内容',
    `source`     VARCHAR(255) COMMENT '来源标注',
    UNIQUE KEY `uk_knowledge_title` (`title`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO `cw_knowledge` (`keyword`, `title`, `content`, `source`) VALUES
('退货,退款,七天,无理由', '七天无理由退货政策', '支持七天无理由退货，商品需保持完好、不影响二次销售；定制类、生鲜类除外。', '《售后服务政策》第 3 条'),
('发票,开票,报销', '发票开具规则', '支持开具电子普通发票与增值税专用发票，可在订单详情页自助申请，1-3 个工作日开具。', '《发票管理规则》第 1 条'),
('运费,包邮,邮费', '运费说明', '单笔订单满 99 元包邮，偏远地区除外；退货运费由责任方承担。', '《运费说明》第 2 条');

-- 敏感词表（SensitiveWordFilter / cw_sensitive_word）：智能路由中控"一次拦截"词库。
-- 种子为脱敏占位词（非真实违禁词），覆盖 BLOCK/MASK/REVIEW 三种动作与多类目。
CREATE TABLE IF NOT EXISTS `cw_sensitive_word` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `word`           VARCHAR(128) NOT NULL COMMENT '敏感词原词面',
    `category`       VARCHAR(32) NOT NULL COMMENT '类目: POLITICS/PORN/ABUSE/COMPETITOR/CUSTOM',
    `action`         VARCHAR(16) NOT NULL COMMENT '处置动作: BLOCK/MASK/REVIEW',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_sensitive_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词表（一次拦截词库）';

INSERT IGNORE INTO `cw_sensitive_word` (`word`, `category`, `action`, `enabled`, `created_at_ms`, `updated_at_ms`) VALUES
('测试敏感词A', 'CUSTOM', 'BLOCK', 1, 1779235200000, 1779235200000),
('涉政占位', 'POLITICS', 'BLOCK', 1, 1779235200000, 1779235200000),
('辱骂占位', 'ABUSE', 'BLOCK', 1, 1779235200000, 1779235200000),
('竞品XX', 'COMPETITOR', 'MASK', 1, 1779235200000, 1779235200000),
('复核占位', 'CUSTOM', 'REVIEW', 1, 1779235200000, 1779235200000);

-- 坐席库表（MybatisSeatAgentStore / cw_seat_agent）：智能路由中控"工单智能分配"的候选坐席池。
-- skills 为逗号分隔技能标签串；seat_group 避开 SQL 保留字 group；种子为演示坐席（多技能/负载/在离线）。
CREATE TABLE IF NOT EXISTS `cw_seat_agent` (
    `id`             VARCHAR(64) PRIMARY KEY COMMENT '坐席ID',
    `name`           VARCHAR(64) NOT NULL COMMENT '坐席名',
    `skills`         VARCHAR(512) COMMENT '技能标签（逗号分隔，如 refund,invoice）',
    `max_load`       INT NOT NULL DEFAULT 0 COMMENT '最大并发工单数',
    `current_load`   INT NOT NULL DEFAULT 0 COMMENT '当前在处理工单数',
    `online`         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否在线: 1在线/0离线',
    `seat_group`     VARCHAR(64) COMMENT '坐席分组',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    INDEX `idx_seat_online` (`online`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席库（智能分配候选坐席池）';

INSERT IGNORE INTO `cw_seat_agent` (`id`, `name`, `skills`, `max_load`, `current_load`, `online`, `seat_group`, `created_at_ms`, `updated_at_ms`) VALUES
('SEAT-1001', '退款专员-小赵', 'refund,invoice', 5, 1, 1, 'aftersales', 1779235200000, 1779235200000),
('SEAT-1002', '物流专员-小钱', 'logistics', 5, 3, 1, 'logistics', 1779235200000, 1779235200000),
('SEAT-1003', '投诉专员-小孙', 'complaint,refund', 4, 0, 1, 'complaint', 1779235200000, 1779235200000),
('SEAT-1004', '综合坐席-小李', 'refund,logistics,complaint,invoice', 6, 5, 1, 'general', 1779235200000, 1779235200000),
('SEAT-1005', '离线坐席-小周', 'refund', 5, 0, 0, 'aftersales', 1779235200000, 1779235200000);

-- 人机切换工单智能分配增强列（cw_handoff_ticket 已在上文建表时含 category/required_skill/priority/emotion/suggested_assignees；
-- 旧库存量表可手工执行下列 ALTER 补列，MySQL 无 ADD COLUMN IF NOT EXISTS，重复执行报 Duplicate column 可忽略）：
-- ALTER TABLE `cw_handoff_ticket`
--   ADD COLUMN `category` VARCHAR(64) NULL COMMENT '工单分类（LLM 分类，可空）',
--   ADD COLUMN `required_skill` VARCHAR(64) NULL COMMENT '所需坐席技能标签（LLM 分类，可空）',
--   ADD COLUMN `priority` VARCHAR(16) NULL COMMENT '优先级 LOW/MEDIUM/HIGH/URGENT（LLM 分类，可空）',
--   ADD COLUMN `emotion` VARCHAR(32) NULL COMMENT '用户情绪（LLM 分类，可空）',
--   ADD COLUMN `suggested_assignees` TEXT NULL COMMENT '推荐坐席列表 JSON（HITL 推荐，可空）';
