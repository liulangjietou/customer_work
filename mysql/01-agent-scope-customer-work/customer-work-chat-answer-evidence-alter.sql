SET NAMES utf8mb4;

-- 客户聊天消息是高写入表；大表部署前确认在线加列能力，必要时安排停写窗口。
-- 已按完整镜像或 Flyway V27 建成的库可重复执行，旧消息不回填。
SET @answer_evidence_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cw_chat_message' AND COLUMN_NAME = 'answer_evidence'
);
SET @answer_evidence_ddl := IF(@answer_evidence_exists = 0,
    'ALTER TABLE cw_chat_message ADD COLUMN answer_evidence JSON NULL COMMENT ''答复终态、来源线索、任务进度及实际检索引用''',
    'SELECT 1');
PREPARE answer_evidence_stmt FROM @answer_evidence_ddl;
EXECUTE answer_evidence_stmt;
DEALLOCATE PREPARE answer_evidence_stmt;
