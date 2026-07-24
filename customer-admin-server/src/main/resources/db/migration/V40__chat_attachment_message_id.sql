-- =============================================================================
-- 对话附件消息绑定（Flyway V40，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 落地"对话附件预览"需求：附件上传时会话尚未产生用户消息，只能先落库（V21 的 ai_chat_attachment）；
-- 随后附件随普通消息一起发送，此时才知道其绑定的用户消息 ID（框架 Msg.id）。新增 message_id 列承载
-- 这层"消息↔附件"关联：随消息发送时由 admin 私有链路（ChatService 请求线程同步段）UPDATE 回填，
-- 供历史接口按 message_id 分组把附件挂回对应消息。空串=未绑定（仅上传未发送 / 旧数据）。
--
-- 查询沿用既有 idx_ai_attachment_session（历史读取先按 session_id 捞该会话全部附件，再在应用层按
-- message_id 分组），无需为 message_id 单建索引。放 session_id 列之后，与 starter 的 cw_chat_attachment 对齐。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响）。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `ai_chat_attachment`
    ADD COLUMN `message_id` VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT '绑定的用户消息ID（框架Msg.id，空=未绑定）' AFTER `session_id`;
