-- =============================================================================
-- admin 库的调用统计两表补 cached_tokens / model_reported_ms（Flyway V44，生产手工同步）
-- =============================================================================
-- 补 V43 的漏：cw_agent_call_log / cw_agent_call_segment 这两张表**两个库各有一份**——
-- 客服端库 agent_scope_customer_work（8080 客服链路写入）与 admin 库 customer_admin
-- （V38/V39 建，后台工作区对话写入）。AgentCallStatsService 按 source 参数路由：
-- ADMIN 查 admin 库、APP 查客服端库，而**默认值是 ADMIN**。
--
-- V43 只补了客服端库那份（走 schema.sql 的手工 ALTER），admin 库这份没动，
-- 于是后台"调用统计"页一打开就报 Unknown column 'cached_tokens'。
--
-- 教训记在这里：cw_ 前缀的同名表凡是同时存在于两个库的，加列必须两边都改——
-- 客服端库走 customer-work-schema.sql 的注释式 ALTER（SchemaInitializer 不会加列），
-- admin 库走 Flyway 迁移。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响）。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `cw_agent_call_log`
    ADD COLUMN `cached_tokens` BIGINT DEFAULT NULL
        COMMENT '命中缓存的输入token（input_tokens的子集，不计入total）' AFTER `total_tokens`,
    ADD COLUMN `model_reported_ms` BIGINT DEFAULT NULL
        COMMENT '模型自报耗时合计（毫秒），与model_ms之差=网络/排队开销' AFTER `cached_tokens`;

ALTER TABLE `cw_agent_call_segment`
    ADD COLUMN `cached_tokens` BIGINT DEFAULT NULL
        COMMENT '命中缓存的输入token（仅MODEL段）' AFTER `output_tokens`,
    ADD COLUMN `model_reported_ms` BIGINT DEFAULT NULL
        COMMENT '模型自报耗时（毫秒，仅MODEL段）' AFTER `cached_tokens`;
