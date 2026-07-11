-- =============================================================================
-- 对话历史持久化：AgentScope 框架自带的 MysqlAgentStateStore 表结构
-- （表结构定义见 io.agentscope.extensions.mysql.state.MysqlAgentStateStore 类注释，
-- 这里用自定义表名 ai_chat_session_state 而非框架默认的 agentscope_sessions，
-- 与本模块 ai_/sys_ 前缀的命名约定保持一致；框架侧 autoCreate 关闭，改由 Flyway/DBA 管理建表，
-- 与本模块"生产不自动建表"的既有约定一致）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_chat_session_state` (
    `session_id`  VARCHAR(255) NOT NULL,
    `state_key`   VARCHAR(255) NOT NULL,
    `item_index`  INT NOT NULL DEFAULT 0,
    `state_data`  LONGTEXT NOT NULL,
    `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`, `state_key`, `item_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话状态持久化（AgentStateStore，含短期记忆/对话历史）';
