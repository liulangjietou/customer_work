-- =============================================================================
-- MCP 连通性测试字段（对齐 ai_model_config 已有的 test_status/test_time 设计）
-- =============================================================================

ALTER TABLE `ai_mcp`
    ADD COLUMN `test_status` TINYINT NOT NULL DEFAULT 0 COMMENT '连通性测试：0未测试 / 1成功 / 2失败' AFTER `status`,
    ADD COLUMN `test_time`   DATETIME NULL COMMENT '最近一次测试时间' AFTER `test_status`;
