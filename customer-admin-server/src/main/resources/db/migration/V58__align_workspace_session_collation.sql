-- ai_chat_session_state.session_id 由 V4 固定为 utf8mb4_unicode_ci；
-- V56 未显式指定排序规则，在 MySQL 8 默认库上会继承 utf8mb4_0900_ai_ci，
-- 两列 JOIN 时触发 Illegal mix of collations。统一整张归属表，避免其他字符串列留下同类隐患。
ALTER TABLE `ai_workspace_session`
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
