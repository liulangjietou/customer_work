-- =============================================================================
-- 智能体跨会话长期记忆（默认存储后端）
-- Harness 分层记忆的工作副本是 workspace/MEMORY.md（框架硬编码写磁盘），本表是它的权威存储：
-- 构建智能体实例时从本表水合到 workspace，对话轮次结束后把变更同步回本表。
-- 配置 admin.agent-memory.disk-root 后改走磁盘存储，本表不再读写（见 AgentMemoryStoreConfig）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_agent_memory` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `agent_code`  VARCHAR(64) NOT NULL COMMENT '智能体编码（ai_agent.agent_code）',
    `content`     LONGTEXT COMMENT '长期记忆内容（MEMORY.md 全文）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_code` (`agent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体跨会话长期记忆（MEMORY.md 权威存储）';
-- =============================================================================
-- 智能体跨会话长期记忆（默认存储后端）
-- Harness 分层记忆的工作副本是 workspace/MEMORY.md（框架硬编码写磁盘），本表是它的权威存储：
-- 构建智能体实例时从本表水合到 workspace，对话轮次结束后把变更同步回本表。
-- 配置 admin.agent-memory.disk-root 后改走磁盘存储，本表不再读写（见 AgentMemoryStoreConfig）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_agent_memory` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `agent_code`  VARCHAR(64) NOT NULL COMMENT '智能体编码（ai_agent.agent_code）',
    `content`     LONGTEXT COMMENT '长期记忆内容（MEMORY.md 全文）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_code` (`agent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体跨会话长期记忆（MEMORY.md 权威存储）';
