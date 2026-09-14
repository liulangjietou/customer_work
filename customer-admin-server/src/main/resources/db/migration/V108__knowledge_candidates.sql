-- 编辑中的候选不写正式 FAQ；每次保存产生不可变修订，供后续评测和发布绑定。
CREATE TABLE ai_knowledge_candidate (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT '当前候选处理状态',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_knowledge_candidate_source (tenant_id, question_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识缺口候选';

CREATE TABLE ai_knowledge_candidate_revision (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    source_review_revision BIGINT NOT NULL COMMENT '保存时已核对的人工分类修订',
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    keyword VARCHAR(255) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    edited_by BIGINT NOT NULL COMMENT '实际登录操作人',
    edited_at_ms BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, candidate_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识候选不可变修订';
