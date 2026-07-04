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
