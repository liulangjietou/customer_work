-- 正式 FAQ 和回执必须在客服库同事务提交；后台父改进记录保存可重放的发布意图。
CREATE TABLE IF NOT EXISTS cw_knowledge_publication_lock (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识候选发布租户串行锁';

CREATE TABLE IF NOT EXISTS cw_knowledge_publication (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    improvement_id BIGINT NOT NULL,
    candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_revision BIGINT NOT NULL,
    artifact_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evaluation_run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_review_revision BIGINT NOT NULL,
    command_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    published_at_ms BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    PRIMARY KEY (tenant_id, task_id),
    UNIQUE KEY uk_knowledge_publication_candidate (tenant_id, candidate_id, candidate_revision),
    UNIQUE KEY uk_knowledge_publication_faq (knowledge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识候选正式发布回执';
