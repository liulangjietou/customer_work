-- =============================================================================
-- SQL 参数日期格式（Flyway V34，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- sql_define_param 新增 date_format 列：仅 DATETIME 类型参数生效，指定查询页日期
-- 控件的选择粒度与提交格式（如 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd）。空表示沿用
-- 默认 yyyy-MM-dd HH:mm:ss，存量数据行为不变。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT
-- 字节级写坏，故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响）。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `sql_define_param`
    ADD COLUMN `date_format` VARCHAR(32) NULL COMMENT '日期格式（仅 DATETIME 类型生效，如 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd；空默认 yyyy-MM-dd HH:mm:ss）' AFTER `param_type`;
