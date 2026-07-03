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
