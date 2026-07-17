-- =============================================================================
-- 渠道-客服机器人运行配置绑定（Flyway V22，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 把某个渠道（channel_code）绑定到一个智能体（agent_id），标记「该智能体的模型/提示词/MCP 配置
-- 即客服机器人（8080）的运行时配置」。绑定后，智能体或其引用模型在后台被改动并通过连通性探测，
-- admin 会把该智能体的运行时配置发布到 Nacos，8080 监听热生效（CustomerWorkConfigPublisher）。
-- channel_code 唯一：一个渠道只对应一份运行时配置来源。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_channel_binding` (
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `channel_code` VARCHAR(64)  NOT NULL COMMENT '渠道编码（唯一，如 default/wechat/web）',
    `agent_id`     BIGINT       NOT NULL COMMENT '绑定的智能体ID（其配置作为客服机器人运行时配置）',
    `status`       INT          NOT NULL DEFAULT 1 COMMENT '0停用 / 1启用（停用则不参与自动发布）',
    `create_by`    BIGINT       DEFAULT NULL COMMENT '创建人',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    BIGINT       DEFAULT NULL COMMENT '更新人',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      INT          NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否/1是',
    UNIQUE KEY `uk_channel_code` (`channel_code`, `deleted`),
    KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道-客服机器人运行配置绑定';
