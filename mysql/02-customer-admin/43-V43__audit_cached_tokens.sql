-- =============================================================================
-- AI 编码审计日志补 cached_tokens（Flyway V43，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 框架 ChatUsage 一直返回 cachedTokens（命中 prompt 缓存的输入 token），但 AiCodingAuditService#applyUsage
-- 此前只取了 input/output/total 三个字段——采到了、算过了、直接扔掉。
--
-- 为什么这个字段值得单独存：cachedTokens 是 input_tokens 的**子集**（不是额外量），各家计价通常只按
-- 1/10 左右收。不留它就有两个后果：一是成本核算永远偏高（把缓存命中的部分按全价算），二是看不出
-- prompt 缓存到底有没有生效——命中率长期为 0 说明系统提示词或历史每次都在变，那笔本可省下的钱一直在白花。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响）。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `ai_coding_audit_log`
    ADD COLUMN `cached_tokens` BIGINT DEFAULT NULL
        COMMENT '命中缓存的输入token（input_tokens的子集，不计入total_tokens）' AFTER `total_tokens`;
