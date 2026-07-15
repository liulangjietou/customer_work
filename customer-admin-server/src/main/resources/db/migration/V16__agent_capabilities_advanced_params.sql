SET NAMES utf8mb4;

-- 智能体高级参数：全部可空，null = 使用框架/工厂默认值
ALTER TABLE `ai_agent`
    ADD COLUMN `max_iters`             INT NULL COMMENT 'ReAct 最大迭代轮数（null=默认10）',
    ADD COLUMN `tool_timeout_seconds`  INT NULL COMMENT '工具执行超时秒数（null=框架默认5分钟）',
    ADD COLUMN `tool_max_attempts`     INT NULL COMMENT '工具执行最大尝试次数（null=框架默认1次）',
    ADD COLUMN `compress_trigger_msgs` INT NULL COMMENT '上下文压缩触发消息数（null=不启用压缩）',
    ADD COLUMN `compress_keep_msgs`    INT NULL COMMENT '压缩后保留最近消息数（null=默认10）';

-- 智能体-子智能体关联（纯关系表，建表风格与 V1 的 ai_agent_mcp 保持一致）
CREATE TABLE IF NOT EXISTS `ai_agent_sub_agent` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `agent_id`      BIGINT NOT NULL COMMENT '父智能体ID',
    `sub_agent_id`  BIGINT NOT NULL COMMENT '子智能体ID',
    UNIQUE KEY `uk_ai_agent_sub_agent` (`agent_id`, `sub_agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-子智能体关联';
