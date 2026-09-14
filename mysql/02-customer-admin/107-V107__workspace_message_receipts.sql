-- 消息受理只存内容指纹和终态；正文与附件仍沿用既有历史和附件表。
CREATE TABLE ai_workspace_message_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '受理记录主键',
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务端租户',
    owner_user_id BIGINT NOT NULL COMMENT '实际发起用户',
    agent_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '智能体编码',
    session_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '会话标识',
    channel VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'chat 或 vibecoding',
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端稳定消息 UUID',
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始请求 SHA256',
    accepted_at_ms BIGINT NOT NULL COMMENT '持久受理时间',
    terminal_phase VARCHAR(32) NULL COMMENT '为空时结果尚未记录',
    turn_id VARCHAR(128) NULL COMMENT '框架用户消息标识',
    message_id VARCHAR(128) NULL COMMENT '框架助手消息标识',
    finish_reason VARCHAR(128) NULL COMMENT '框架终止原因',
    history_saved TINYINT(1) NULL COMMENT '历史回读事实',
    artifacts_saved TINYINT(1) NULL COMMENT '产物保存事实',
    knowledge_sources_saved TINYINT(1) NULL COMMENT '来源保存事实',
    error_message TEXT NULL COMMENT '可展示的结果核对提示',
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_message_owner (tenant_id,owner_user_id,client_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作区持久消息受理';
