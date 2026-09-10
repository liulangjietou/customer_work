-- 首行强制 utf8mb4，避免走 stdin 管道时客户端字符集回退 latin1 把中文 COMMENT 字节级写坏（见 mysql/README.md）
SET NAMES utf8mb4;

-- ============================================================================
-- 增量迁移：agentscope_sessions 增加 version 列（AgentScope 2.0.3 乐观并发写入的 CAS 依据）
--
-- 【什么时候需要执行它】
-- 只有「应用数据库账号没有 DDL 权限」的生产部署需要，由 DBA 在升级到 2.0.3 之前执行一次。
-- 应用账号有 DDL 权限时不必执行：MysqlAgentStateStore 的构造器会自己补这一列。
--
-- 【不执行会怎样】
-- 2.0.3 的 MysqlAgentStateStore#supportsVersioning() 硬编码返回 true，框架因此默认走
-- getVersioned / saveIfVersion 这条路径；而它的构造器里那句 ensureVersionColumn() 会尝试
--     ALTER TABLE agentscope_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0
-- 补列（反编译确认：这一步在 auto-create 分支汇合之后，`session.mysql.auto-create=false`
-- 时照样执行），失败会把 SQLException 包成 RuntimeException 抛出——**构造器失败，应用起不来**。
--
-- 【为什么这张表没有 Flyway 迁移】
-- agentscope_sessions 由框架内置 DDL 自建，不属于 cw_* 业务表，因此既不在
-- db/customerwork/migration 里，也刻意不进 mysql/schema-snapshot（见 CLAUDE.md 结构快照一节）。
-- 后台管理库那张同类表 ai_chat_session_state 归 Flyway 管，走 admin 的 V102 迁移，不用这个脚本。
--
-- 【幂等】按 information_schema 判定，列已存在则跳过。
-- 【锁】纯 ADD COLUMN，MySQL 8 走 INSTANT 算法，不重建表、不阻塞读写。
-- 【列定义与框架保持逐字一致】BIGINT NOT NULL DEFAULT 0——框架只检查列名存在与否，
--   类型不一致会在 saveIfVersion 的 CAS 上表现为难查的行为差异。
-- ============================================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agentscope_sessions'
      AND COLUMN_NAME = 'version'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `agentscope_sessions` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号（AgentScope 2.0.3 saveIfVersion 的 CAS 依据）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
