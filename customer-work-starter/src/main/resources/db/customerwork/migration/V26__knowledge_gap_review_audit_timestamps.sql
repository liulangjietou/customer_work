-- V25 已在本机开发库执行，通用审计字段通过新迁移补齐，保留原始复核时间与全部历史。
ALTER TABLE cw_knowledge_gap_review
    ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间';
