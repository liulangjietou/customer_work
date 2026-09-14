-- 区分同一候选的多次复评，重启后的超时扫描不能被旧请求的迟到结果覆盖。
ALTER TABLE ai_agent_improvement_case
    ADD COLUMN reevaluation_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次复评唯一执行编号',
    ADD COLUMN reevaluation_deadline_at_ms BIGINT NULL COMMENT '本次复评最晚完成时间';

-- 升级前运行没有可核对的执行编号；保留已完成事实，只让这些在途任务明确失败后由运营重新发起。
UPDATE ai_agent_improvement_case
SET status='REEVALUATION_FAILED', reevaluation_status='FAILED',
    reevaluation_error='升级前复评没有可恢复的执行记录，请核对候选后重新发起',
    next_action_at_ms=9223372036854775807, lease_owner=NULL, lease_until_ms=0
WHERE status='REEVALUATING';

ALTER TABLE ai_agent_improvement_case
    ADD CONSTRAINT chk_improvement_reevaluation_attempt CHECK (
        status <> 'REEVALUATING' OR (reevaluation_attempt_id IS NOT NULL
            AND reevaluation_deadline_at_ms IS NOT NULL AND reevaluation_deadline_at_ms > 0));
