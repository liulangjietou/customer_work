-- =============================================================================
-- AgentScope 智能客服系统 · 会话持久化 MySQL 建库建表脚本
-- =============================================================================
-- 说明：
--   1. MysqlSession 在 autoCreate=true 时会自动建库建表，本脚本用于手工初始化 /
--      DBA 审核 / 受限权限环境（生产 DB 账号通常无建库权限）。
--   2. 表结构与框架 io.agentscope.core.session.mysql.MysqlSession 内置 DDL 完全一致。
--   3. 连接信息（默认）：host=localhost:3306, user=root, password=root,
--      database=agent_scope_customer_work。
--
-- 执行：mysql -h localhost -u root -proot < mysql/schema.sql
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
-- 说明：以下 6 张表分别由 JdbcTicketStore / JdbcUserAccountStore / JdbcChatMessageStore /
--       JdbcOrderBackend / JdbcProductBackend 自动建表（CREATE TABLE IF NOT EXISTS），
--       本脚本用于 DBA 预审 / 受限权限环境；列/索引与各 JdbcStore 的 ensureTable 一致。
--       cw_product / cw_order 的种子数据与各 Jdbc 后端 INSERT IGNORE 的种子完全一致
--       （含 Mock 示例订单 20260613001 / 20260613002），保证从 Mock 切到 JDBC 后系统提示词示例连续。

-- 终端用户账户表（cw_user）：登录凭据以 BCrypt 哈希存储。
CREATE TABLE IF NOT EXISTS `cw_user` (
    `id`             VARCHAR(64) PRIMARY KEY COMMENT '用户ID',
    `username`       VARCHAR(64) NOT NULL COMMENT '用户名',
    `password_hash`  VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname`       VARCHAR(64) COMMENT '昵称',
    `phone`          VARCHAR(32) COMMENT '手机号',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
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
