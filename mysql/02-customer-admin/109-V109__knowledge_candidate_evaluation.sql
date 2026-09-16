-- 复用 ai_agent_improvement_case 状态机，以下只保存其引用的不可变候选输入和评测事实。
CREATE TABLE ai_knowledge_candidate_binding (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    improvement_id BIGINT NOT NULL,
    artifact_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_revision BIGINT NOT NULL,
    input_json LONGTEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, improvement_id, artifact_fingerprint),
    KEY idx_candidate_binding_revision (tenant_id, candidate_id, candidate_revision),
    CONSTRAINT chk_knowledge_binding_json CHECK (JSON_VALID(input_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='改进记录引用的知识候选输入';

CREATE TABLE ai_knowledge_candidate_evaluation (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    improvement_id BIGINT NOT NULL,
    artifact_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evaluation_json LONGTEXT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    KEY idx_candidate_eval_binding (tenant_id, improvement_id, artifact_fingerprint, created_at_ms),
    CONSTRAINT chk_knowledge_evaluation_json CHECK (JSON_VALID(evaluation_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识候选基线与实际评测事实';
