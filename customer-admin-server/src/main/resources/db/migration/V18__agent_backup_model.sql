-- =============================================================================
-- 智能体模型主备容错（Flyway V16，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 智能体沿用 ai_agent.model_id 作为「主模型」，另挂 0..N 个「有序备用模型」（本表）。
-- 运行时主模型调用失败按 sort_order 升序切换备模型；关联维护走整体替换式（与 ai_agent_mcp/skill 一致）。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_agent_backup_model` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `agent_id`    BIGINT NOT NULL COMMENT '智能体ID',
    `model_id`    BIGINT NOT NULL COMMENT '备用模型ID',
    `sort_order`  INT NOT NULL DEFAULT 0 COMMENT '容错切换顺序（升序，越小越先尝试）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_agent_backup` (`agent_id`, `model_id`),
    KEY `idx_backup_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-备用模型关联';
