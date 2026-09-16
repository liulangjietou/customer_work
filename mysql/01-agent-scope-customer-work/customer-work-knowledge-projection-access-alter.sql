SET NAMES utf8mb4;

-- 旧投影默认封锁，需由后台重新核对当前公开权限后投影；不从历史 PUBLIC 推断当前授权。
-- 只新增迁移，手工镜像与 Flyway 可重复接管；同一版本重新投影保留分片主键。

SET @projection_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cw_knowledge_version' AND COLUMN_NAME = 'access_status');
SET @projection_ddl := IF(@projection_column_exists = 0, 'ALTER TABLE cw_knowledge_version ADD COLUMN access_status VARCHAR(16) NOT NULL DEFAULT ''BLOCKED'' COMMENT ''客户授权投影状态：BLOCKED/READY''', 'SELECT 1');
PREPARE projection_stmt FROM @projection_ddl;
EXECUTE projection_stmt;
DEALLOCATE PREPARE projection_stmt;

SET @projection_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cw_knowledge_version' AND COLUMN_NAME = 'version_no');
SET @projection_ddl := IF(@projection_column_exists = 0, 'ALTER TABLE cw_knowledge_version ADD COLUMN version_no INT NULL COMMENT ''后台知识库版本号''', 'SELECT 1');
PREPARE projection_stmt FROM @projection_ddl;
EXECUTE projection_stmt;
DEALLOCATE PREPARE projection_stmt;

SET @projection_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cw_knowledge_chunk' AND COLUMN_NAME = 'document_title');
SET @projection_ddl := IF(@projection_column_exists = 0, 'ALTER TABLE cw_knowledge_chunk ADD COLUMN document_title VARCHAR(512) NULL COMMENT ''历史文档修订标题''', 'SELECT 1');
PREPARE projection_stmt FROM @projection_ddl;
EXECUTE projection_stmt;
DEALLOCATE PREPARE projection_stmt;

SET @projection_column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cw_knowledge_chunk' AND COLUMN_NAME = 'source_version');
SET @projection_ddl := IF(@projection_column_exists = 0, 'ALTER TABLE cw_knowledge_chunk ADD COLUMN source_version VARCHAR(255) NULL COMMENT ''历史文档上游版本''', 'SELECT 1');
PREPARE projection_stmt FROM @projection_ddl;
EXECUTE projection_stmt;
DEALLOCATE PREPARE projection_stmt;

SET @projection_index_columns := (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
    FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cw_knowledge_chunk' AND INDEX_NAME = 'uk_cw_kb_chunk');
SET @projection_ddl := IF(@projection_index_columns = 'tenant_id,kb_version_id,doc_revision_id,chunk_index',
    'SELECT 1', CONCAT('ALTER TABLE cw_knowledge_chunk ',
        IF(@projection_index_columns IS NULL, '', 'DROP INDEX uk_cw_kb_chunk, '),
        'ADD UNIQUE KEY uk_cw_kb_chunk (tenant_id,kb_version_id,doc_revision_id,chunk_index)'));
PREPARE projection_stmt FROM @projection_ddl;
EXECUTE projection_stmt;
DEALLOCATE PREPARE projection_stmt;
