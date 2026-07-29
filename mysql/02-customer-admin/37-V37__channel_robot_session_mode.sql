-- =============================================================================
-- 渠道机器人会话模式（Flyway V37）
-- =============================================================================
-- 机器人级可配置的会话模式：
--   continuous  持续会话（默认）：同一外部用户复用同一 sessionId，多轮携带上下文；
--   per_message 单次问答：每条消息独立会话，不携带历史上下文（customer-channel 侧本地生成
--               一次性 sessionId，不经 ai_channel_session 映射）。
-- 手工同步注意：同 V36，首行 SET NAMES utf8mb4 防中文 COMMENT 走 stdin 管道时字节级写坏。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `ai_channel_robot`
    ADD COLUMN `session_mode` VARCHAR(16) NOT NULL DEFAULT 'continuous'
        COMMENT '会话模式：continuous 持续会话 / per_message 单次问答' AFTER `agent_code`;
