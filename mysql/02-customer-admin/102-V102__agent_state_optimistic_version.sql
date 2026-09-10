-- 会话状态表补 version 列：承接 AgentScope 2.0.3 的乐观并发写入。
--
-- 【为什么必须由迁移来加，而不是让框架自己加】
-- 2.0.3 给 AgentStateStore 加了乐观并发（getVersioned / saveIfVersion），MysqlAgentStateStore 的
-- supportsVersioning() 直接硬编码返回 true，且它的**构造器**会执行
--     ALTER TABLE <表> ADD COLUMN version BIGINT NOT NULL DEFAULT 0
-- 来自动补列——注意这一步在 autoCreate=false 时**照样执行**（反编译确认：createTableIfNotExist
-- 在 autoCreate 分支里，ensureVersionColumn 在分支汇合之后无条件调用）。
--
-- 而本模块的既有约定是"生产不自动建表"（V4 的注释写明，AdminAgentRuntimeConfig 传 createIfNotExist=false，
-- 库结构一律由 Flyway 与 mysql/02 镜像管理）。两件事撞在一起会有两种坏结果：
--   1. 应用账号有 DDL 权限：框架在启动时静默改了一张归 Flyway 管的表，结构从此与迁移产物不一致，
--      结构快照门禁会红，而红的原因与任何人的改动都无关；
--   2. 应用账号只有 DML 权限（生产的正常配置）：ensureVersionColumn 把 SQLException 包成
--      RuntimeException 抛出，**构造器失败，应用直接起不来**。
-- 先由迁移把列加好，框架那一步查到列已存在就跳过，两种坏结果都不发生。
--
-- 【幂等】按 information_schema 判定，列已存在则空跑；从完整镜像初始化的库同样安全。
-- 【锁】纯 ADD COLUMN，MySQL 8 走 INSTANT 算法，不重建表、不阻塞读写。
-- 【列定义与框架保持逐字一致】BIGINT NOT NULL DEFAULT 0——框架只检查列名存在与否，
-- 但类型不一致会在 saveIfVersion 的 CAS 上表现为难查的行为差异。

SET NAMES utf8mb4;

SET @v102_version_col = (SELECT COUNT(*) FROM `information_schema`.`COLUMNS`
    WHERE `TABLE_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'ai_chat_session_state'
      AND `COLUMN_NAME` = 'version');

SET @v102_sql = IF(@v102_version_col > 0, 'SELECT 1',
    'ALTER TABLE `ai_chat_session_state` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号（AgentScope 2.0.3 saveIfVersion 的 CAS 依据）''');

PREPARE v102_stmt FROM @v102_sql;
EXECUTE v102_stmt;
DEALLOCATE PREPARE v102_stmt;
