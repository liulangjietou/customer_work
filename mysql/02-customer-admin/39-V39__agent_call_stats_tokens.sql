-- =============================================================================
-- 智能体调用分段耗时统计——token 消耗采集（Flyway V39）
-- =============================================================================
-- 在 V38 建的两张表上补 token 列：主记录加请求级 input/output/total token，分段明细加 input/output token
-- （仅 MODEL 段有值）。采集口径与 admin 审计模块一致，均取自框架 ModelCallEndEvent 携带的 ChatUsage。
-- token 缺失（离线/框架未上报）时列值 NULL，区分"未采到"与"用了 0 token"。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
-- 本迁移无 DML 种子，仅 ALTER。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `cw_agent_call_log`
    ADD COLUMN `input_tokens`  BIGINT DEFAULT NULL COMMENT '请求级输入token合计（缺失为NULL）' AFTER `segment_count`,
    ADD COLUMN `output_tokens` BIGINT DEFAULT NULL COMMENT '请求级输出token合计（缺失为NULL）' AFTER `input_tokens`,
    ADD COLUMN `total_tokens`  BIGINT DEFAULT NULL COMMENT '请求级总token合计（缺失为NULL）' AFTER `output_tokens`;

ALTER TABLE `cw_agent_call_segment`
    ADD COLUMN `input_tokens`  BIGINT DEFAULT NULL COMMENT '输入token（仅MODEL段，缺失为NULL）' AFTER `duration_ms`,
    ADD COLUMN `output_tokens` BIGINT DEFAULT NULL COMMENT '输出token（仅MODEL段，缺失为NULL）' AFTER `input_tokens`;
