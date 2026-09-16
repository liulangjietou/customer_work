-- 聊天来源仅存本轮检索元数据，原文始终按当前权限读取不可变修订。
CREATE TABLE IF NOT EXISTS ai_chat_knowledge_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '来源记录主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
    state_user_id VARCHAR(255) NOT NULL COMMENT '权威会话存储的主体分区',
    agent_code VARCHAR(64) NOT NULL COMMENT '主智能体编码',
    session_id VARCHAR(128) NOT NULL COMMENT '工作区会话标识',
    turn_id VARCHAR(128) NOT NULL COMMENT '本轮用户消息标识',
    message_id VARCHAR(128) NOT NULL COMMENT '已确认保存的助手消息标识',
    retrievals JSON NOT NULL COMMENT '实际检索状态与来源元数据，不含正文',
    created_at_ms BIGINT NOT NULL COMMENT '保存时间毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_knowledge_message (tenant_id, state_user_id, session_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作区消息检索依据';
