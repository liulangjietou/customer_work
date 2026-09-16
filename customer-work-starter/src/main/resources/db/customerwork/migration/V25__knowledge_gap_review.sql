-- 保留原始未命中计数，新增最近样本和人工分类。新旧记录均不把未命中等同知识不足。
ALTER TABLE cw_knowledge_gap
    ADD COLUMN retrieval_path VARCHAR(16) NULL COMMENT '最近样本的检索路径',
    ADD COLUMN source_agent_code VARCHAR(128) NULL COMMENT '最近样本的实际智能体',
    ADD COLUMN source_channel_code VARCHAR(64) NULL COMMENT '最近样本的可信入口',
    ADD COLUMN source_session_type VARCHAR(32) NULL COMMENT '最近样本的会话类型',
    ADD COLUMN retrieval_result VARCHAR(16) NULL COMMENT '最近样本的检索结果',
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '运营分类',
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '人工优先级',
    ADD COLUMN classification_origin VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT '规则建议或人工结论',
    ADD COLUMN classification_reason VARCHAR(1000) NOT NULL DEFAULT '只有检索未命中证据，尚不足以判断原因' COMMENT '当前分类理由',
    ADD COLUMN review_revision BIGINT NOT NULL DEFAULT 0 COMMENT '人工复核修订',
    ADD COLUMN reviewed_by VARCHAR(64) NULL COMMENT '复核后台用户ID',
    ADD COLUMN reviewed_at_ms BIGINT NULL COMMENT '最近复核时间';

-- 此规则集冻结在 V25，只对完整问候和时钟问题提供建议。达到旧存储上限的文本可能截断，不自动分类。
UPDATE cw_knowledge_gap SET category = 'NON_BUSINESS', classification_origin = 'RULE',
    classification_reason = '完整提问符合常见问候规则，请按实际业务复核'
WHERE CHAR_LENGTH(question) < 500 AND LOWER(REGEXP_REPLACE(question, '[[:space:]，。！？!?]', '')) IN
    ('你好','您好','你好呀','你好啊','您好呀','早上好','早上好呀','晚上好','下午好','hello','hi');
UPDATE cw_knowledge_gap SET category = 'REALTIME', classification_origin = 'RULE',
    classification_reason = '完整提问仅询问当前日期或时间，静态知识无法保证实时性'
WHERE CHAR_LENGTH(question) < 500 AND LOWER(REGEXP_REPLACE(question, '[[:space:]，。！？!?]', '')) IN
    ('今天几号','今天是几号','今天星期几','今天是星期几','今天周几','现在几点','现在几点钟','今天是什么日期');

CREATE TABLE cw_knowledge_gap_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    scope_id VARCHAR(128) NOT NULL COMMENT '原始信号分区',
    question_hash VARCHAR(64) NOT NULL COMMENT '原始问题哈希',
    revision BIGINT NOT NULL COMMENT '复核修订',
    previous_category VARCHAR(32) NOT NULL COMMENT '修改前类别',
    previous_priority VARCHAR(16) NOT NULL COMMENT '修改前优先级',
    category VARCHAR(32) NOT NULL COMMENT '修改后类别',
    priority VARCHAR(16) NOT NULL COMMENT '修改后优先级',
    reason VARCHAR(1000) NOT NULL COMMENT '人工判断理由',
    reviewed_by VARCHAR(64) NOT NULL COMMENT '复核后台用户ID',
    reviewed_at_ms BIGINT NOT NULL COMMENT '复核时间',
    signal_count BIGINT NOT NULL COMMENT '复核保存时的计数快照',
    UNIQUE KEY uk_gap_review (tenant_id, scope_id, question_hash, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识缺口人工复核流水';
