-- 发布请求人与原任务同库保存，Worker 重放不依赖发起人的在线登录态。
ALTER TABLE ai_agent_improvement_case
    ADD COLUMN publish_requested_by BIGINT NULL COMMENT '知识发布实际发起人',
    DROP CHECK chk_improvement_status,
    ADD CONSTRAINT chk_improvement_status CHECK (status IN (
        'OWNED', 'READY_FOR_REEVALUATION', 'REEVALUATING', 'REEVALUATION_FAILED',
        'READY_TO_PUBLISH', 'PUBLISHING', 'PUBLISHED', 'PUBLISH_FAILED', 'OBSERVING',
        'VERIFIED', 'INEFFECTIVE', 'INCONCLUSIVE', 'CANCELLED'));

-- PUBLISHED 仅表示正式 FAQ 与回执已提交，不表示线上效果已验证。
