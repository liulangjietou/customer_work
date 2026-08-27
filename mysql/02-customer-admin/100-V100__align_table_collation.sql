-- 统一后台管理库剩余不一致表的排序规则为 utf8mb4_unicode_ci。
--
-- 【为什么要做】MySQL 8 下 `DEFAULT CHARSET=utf8mb4` 不带 COLLATE 时，用的是**字符集默认**
-- 即 utf8mb4_0900_ai_ci，而不是建库时 CREATE DATABASE 指定的排序规则。只有连 CHARSET 都不写的
-- 建表语句才继承建库参数。
-- 迁移文件里三种写法并存，导致同库内表间排序规则不一致，跨表比较字符串列时报
-- 1267 Illegal mix of collations。此前 V58、V97 只按需修了涉事的表，
-- ModelImpactMapper 用 COLLATE 字面量、BusinessOutcomeMapper 用 CAST(x AS BINARY) 各自绕过一次——
-- 后者的代价是 session_id 索引失效。本迁移一次性收口，让"下一张表该用什么"有唯一答案。
--
-- 【逐表守卫】已是 utf8mb4_unicode_ci 的表直接跳过，不做无谓重建。因此实际重建的表集合取决于
-- 目标库当前状态：从完整镜像初始化的库多数表已合规，本迁移接近空跑。
-- 执行前用这条 SQL 确认本次会重建哪些表、各多大：
--
--   SELECT t.TABLE_NAME, t.TABLE_COLLATION, t.TABLE_ROWS,
--          ROUND((t.DATA_LENGTH + t.INDEX_LENGTH) / 1024 / 1024) AS SIZE_MB
--     FROM information_schema.TABLES t
--    WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'
--      AND t.TABLE_NAME IN ('ai_agent_task', 'ai_code_review_task', 'ai_site_message',
--                          'cw_agent_call_log', 'cw_agent_call_segment', 'sys_menu_change_log')
--      AND t.TABLE_COLLATION <> 'utf8mb4_unicode_ci'
--    ORDER BY (t.DATA_LENGTH + t.INDEX_LENGTH) DESC;
--
-- 【锁与窗口 · 必读】实测 MySQL 8.0：
--   ALTER ... CONVERT TO ..., ALGORITHM=INPLACE  -> ERROR 1846 Cannot change column type INPLACE
--   ALTER ... CONVERT TO ..., ALGORITHM=COPY, LOCK=NONE -> ERROR 1846 COPY algorithm requires a lock
-- 即每张表都是**全表重建 + LOCK=SHARED**（读不阻塞，写阻塞），没有原生在线选项。
-- 下列高写入表如果数据量大，请安排停写窗口，或改用 gh-ost / pt-online-schema-change 单独处理
-- 后再执行本迁移（届时守卫会跳过它们）：
--     cw_agent_call_log         每次智能体调用一行
--     cw_agent_call_segment     每次模型调用一行，量级高于 call_log
-- 其余表多为配置/低频表，通常可直接执行。
--
-- 注：字符集不变（都是 utf8mb4），CONVERT TO 只改排序规则，不重新编码列数据与中文注释。

SET NAMES utf8mb4;

SET @v100_ai_agent_task = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'ai_agent_task');
SET @v100_ai_agent_task_sql = IF(@v100_ai_agent_task IS NULL OR @v100_ai_agent_task = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `ai_agent_task` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v100_ai_agent_task_stmt FROM @v100_ai_agent_task_sql; EXECUTE v100_ai_agent_task_stmt; DEALLOCATE PREPARE v100_ai_agent_task_stmt;

SET @v100_ai_code_review_task = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'ai_code_review_task');
SET @v100_ai_code_review_task_sql = IF(@v100_ai_code_review_task IS NULL OR @v100_ai_code_review_task = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `ai_code_review_task` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v100_ai_code_review_task_stmt FROM @v100_ai_code_review_task_sql; EXECUTE v100_ai_code_review_task_stmt; DEALLOCATE PREPARE v100_ai_code_review_task_stmt;

SET @v100_ai_site_message = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'ai_site_message');
SET @v100_ai_site_message_sql = IF(@v100_ai_site_message IS NULL OR @v100_ai_site_message = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `ai_site_message` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v100_ai_site_message_stmt FROM @v100_ai_site_message_sql; EXECUTE v100_ai_site_message_stmt; DEALLOCATE PREPARE v100_ai_site_message_stmt;

SET @v100_cw_agent_call_log = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_agent_call_log');
SET @v100_cw_agent_call_log_sql = IF(@v100_cw_agent_call_log IS NULL OR @v100_cw_agent_call_log = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_agent_call_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v100_cw_agent_call_log_stmt FROM @v100_cw_agent_call_log_sql; EXECUTE v100_cw_agent_call_log_stmt; DEALLOCATE PREPARE v100_cw_agent_call_log_stmt;

SET @v100_cw_agent_call_segment = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_agent_call_segment');
SET @v100_cw_agent_call_segment_sql = IF(@v100_cw_agent_call_segment IS NULL OR @v100_cw_agent_call_segment = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_agent_call_segment` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v100_cw_agent_call_segment_stmt FROM @v100_cw_agent_call_segment_sql; EXECUTE v100_cw_agent_call_segment_stmt; DEALLOCATE PREPARE v100_cw_agent_call_segment_stmt;

SET @v100_sys_menu_change_log = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'sys_menu_change_log');
SET @v100_sys_menu_change_log_sql = IF(@v100_sys_menu_change_log IS NULL OR @v100_sys_menu_change_log = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `sys_menu_change_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v100_sys_menu_change_log_stmt FROM @v100_sys_menu_change_log_sql; EXECUTE v100_sys_menu_change_log_stmt; DEALLOCATE PREPARE v100_sys_menu_change_log_stmt;

-- 收尾自检：逐表守卫可能因权限或锁失败而静默跳过，这里让残留直接炸掉迁移。
SET @v100_remaining = (SELECT COUNT(*) FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE'
      AND `TABLE_NAME` IN ('ai_agent_task', 'ai_code_review_task', 'ai_site_message', 'cw_agent_call_log', 'cw_agent_call_segment', 'sys_menu_change_log')
      AND `TABLE_COLLATION` <> 'utf8mb4_unicode_ci');
SET @v100_verify_sql = IF(@v100_remaining = 0, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v100_collation_not_aligned__`');
PREPARE v100_verify_stmt FROM @v100_verify_sql;
EXECUTE v100_verify_stmt;
DEALLOCATE PREPARE v100_verify_stmt;
