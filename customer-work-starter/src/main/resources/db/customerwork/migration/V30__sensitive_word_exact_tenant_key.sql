-- 词面继续使用原来的业务排序规则，租户标识单独按大小写精确比较。
-- 原唯一键把大小写不同的租户合并为同一词条，upsert 会覆盖原记录及其归属。
-- 仅修改身份列，不转换整表、不改写历史词面、动作或启用状态。
SET @v30_word_tenant_collation = (SELECT COLLATION_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cw_sensitive_word' AND COLUMN_NAME = 'tenant_id');
SET @v30_word_tenant_sql = IF(@v30_word_tenant_collation = 'utf8mb4_bin', 'SELECT 1',
    'ALTER TABLE cw_sensitive_word MODIFY tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT ''default'' COMMENT ''租户ID（多租户行级隔离）''');
PREPARE v30_word_tenant_stmt FROM @v30_word_tenant_sql;
EXECUTE v30_word_tenant_stmt;
DEALLOCATE PREPARE v30_word_tenant_stmt;
