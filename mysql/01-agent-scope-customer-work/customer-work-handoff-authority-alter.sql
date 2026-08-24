-- P1-03 DBA 镜像：把三态 cw_handoff_ticket 归并到完整 cw_ticket；旧表迁移后仅作只读归档。
-- 与 Flyway V17__consolidate_handoff_authority.sql 保持一致，可在任意工作目录独立执行。
SET @v17_ticket_exists = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_ticket'
);
SET @v17_handoff_exists = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'cw_handoff_ticket'
);
SET @v17_preflight_sql = IF(@v17_ticket_exists = 1 AND @v17_handoff_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_work_v17_required_table_preflight_failed__`');
PREPARE v17_preflight_stmt FROM @v17_preflight_sql;
EXECUTE v17_preflight_stmt;
DEALLOCATE PREPARE v17_preflight_stmt;

-- 智能分配字段跟随权威工单，业务枚举 category/priority 保持受控，不承载 LLM 任意标签。
SET @v17_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_ticket' AND column_name = 'routing_category'), '',
       ', ADD COLUMN `routing_category` VARCHAR(64) DEFAULT NULL COMMENT ''智能路由分类原文'' AFTER `resolve_note`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_ticket' AND column_name = 'required_skill'), '',
       ', ADD COLUMN `required_skill` VARCHAR(64) DEFAULT NULL COMMENT ''所需坐席技能'' AFTER `routing_category`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_ticket' AND column_name = 'routing_priority'), '',
       ', ADD COLUMN `routing_priority` VARCHAR(16) DEFAULT NULL COMMENT ''智能路由优先级原文'' AFTER `required_skill`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_ticket' AND column_name = 'emotion'), '',
       ', ADD COLUMN `emotion` VARCHAR(32) DEFAULT NULL COMMENT ''用户情绪'' AFTER `routing_priority`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cw_ticket' AND column_name = 'suggested_assignees'), '',
       ', ADD COLUMN `suggested_assignees` TEXT DEFAULT NULL COMMENT ''推荐坐席列表 JSON'' AFTER `emotion`')
);
SET @v17_ddl = IF(@v17_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `cw_ticket` ', SUBSTRING(@v17_columns, 3)));
PREPARE v17_ddl_stmt FROM @v17_ddl;
EXECUTE v17_ddl_stmt;
DEALLOCATE PREPARE v17_ddl_stmt;

DROP TEMPORARY TABLE IF EXISTS `_v17_legacy_handoff_latest`;
CREATE TEMPORARY TABLE `_v17_legacy_handoff_latest` AS
SELECT h.*
FROM `cw_handoff_ticket` h
LEFT JOIN `cw_handoff_ticket` newer
  ON newer.tenant_id = h.tenant_id
 AND newer.session_id = h.session_id
 AND (newer.created_at_ms > h.created_at_ms
      OR (newer.created_at_ms = h.created_at_ms AND newer.id > h.id))
WHERE newer.id IS NULL;

-- 已有同会话 Ticket 时只补齐迁移事实；若 Ticket 已经走过人工链路，绝不让旧三态覆盖新状态机。
UPDATE `cw_ticket` t
INNER JOIN `_v17_legacy_handoff_latest` h
        ON h.tenant_id = t.tenant_id AND h.session_id = t.session_id
LEFT JOIN `cw_ticket` newer
       ON newer.tenant_id = t.tenant_id AND newer.session_id = t.session_id
      AND (newer.created_at_ms > t.created_at_ms
           OR (newer.created_at_ms = t.created_at_ms AND newer.id > t.id))
SET t.status = CASE
        WHEN COALESCE(t.handoff_at_ms, 0) > 0 OR NULLIF(TRIM(t.handoff_reason), '') IS NOT NULL
            THEN t.status
        WHEN h.status = 'PENDING' THEN 'WAITING_AGENT'
        WHEN h.status = 'CLAIMED' THEN 'PROCESSING'
        WHEN h.status = 'RESOLVED' THEN 'RESOLVED'
        ELSE t.status
    END,
    t.assignee = COALESCE(t.assignee, h.claimed_by),
    t.handoff_reason = COALESCE(NULLIF(t.handoff_reason, ''), h.reason),
    t.handoff_at_ms = GREATEST(COALESCE(t.handoff_at_ms, 0), h.created_at_ms),
    t.claimed_at_ms = GREATEST(COALESCE(t.claimed_at_ms, 0), COALESCE(h.claimed_at_ms, 0)),
    t.resolved_at_ms = GREATEST(COALESCE(t.resolved_at_ms, 0), COALESCE(h.resolved_at_ms, 0)),
    t.resolve_note = COALESCE(NULLIF(t.resolve_note, ''), h.resolution_note),
    t.routing_category = COALESCE(t.routing_category, h.category),
    t.required_skill = COALESCE(t.required_skill, h.required_skill),
    t.routing_priority = COALESCE(t.routing_priority, h.priority),
    t.emotion = COALESCE(t.emotion, h.emotion),
    t.suggested_assignees = COALESCE(t.suggested_assignees, h.suggested_assignees),
    t.updated_at_ms = GREATEST(t.updated_at_ms, h.created_at_ms,
        COALESCE(h.claimed_at_ms, 0), COALESCE(h.resolved_at_ms, 0))
WHERE newer.id IS NULL;

-- 没有完整工单的历史 handoff 才补建；沿用原 HO 主键以保留外部引用。
INSERT INTO `cw_ticket`
    (`tenant_id`, `id`, `session_id`, `user_id`, `title`, `category`, `priority`, `status`,
     `assignee`, `handoff_reason`, `resolve_note`, `routing_category`, `required_skill`,
     `routing_priority`, `emotion`, `suggested_assignees`, `reopen_count`, `created_at_ms`,
     `updated_at_ms`, `handoff_at_ms`, `claimed_at_ms`, `resolved_at_ms`, `closed_at_ms`,
     `last_user_active_at_ms`)
SELECT h.tenant_id, h.id, h.session_id, CONCAT('legacy-handoff:', h.session_id),
       LEFT(COALESCE(NULLIF(h.reason, ''), 'Legacy handoff'), 255), 'OTHER', 'NORMAL',
       CASE h.status WHEN 'PENDING' THEN 'WAITING_AGENT' WHEN 'CLAIMED' THEN 'PROCESSING'
            WHEN 'RESOLVED' THEN 'RESOLVED' ELSE 'WAITING_AGENT' END,
       h.claimed_by, h.reason, h.resolution_note, h.category, h.required_skill, h.priority,
       h.emotion, h.suggested_assignees, 0, h.created_at_ms,
       GREATEST(h.created_at_ms, COALESCE(h.claimed_at_ms, 0), COALESCE(h.resolved_at_ms, 0)),
       h.created_at_ms, COALESCE(h.claimed_at_ms, 0), COALESCE(h.resolved_at_ms, 0), 0,
       h.created_at_ms
FROM `_v17_legacy_handoff_latest` h
WHERE h.session_id IS NOT NULL AND h.session_id <> ''
  AND NOT EXISTS (
      SELECT 1 FROM `cw_ticket` t
      WHERE t.tenant_id = h.tenant_id AND t.session_id = h.session_id
  );

-- 每个迁移来源只追加一次审计事件，重复手工执行脚本也不会重复留痕。
INSERT INTO `cw_ticket_event`
    (`tenant_id`, `ticket_id`, `event_type`, `from_status`, `to_status`, `actor_type`,
     `actor_id`, `note`, `created_at_ms`)
SELECT h.tenant_id, t.id, 'HANDOFF_MIGRATED', NULL, t.status, 'SYSTEM',
       'flyway-v17', 'Migrated from legacy handoff authority', h.created_at_ms
FROM `_v17_legacy_handoff_latest` h
INNER JOIN `cw_ticket` t
        ON t.tenant_id = h.tenant_id AND t.session_id = h.session_id
LEFT JOIN `cw_ticket` newer
       ON newer.tenant_id = t.tenant_id AND newer.session_id = t.session_id
      AND (newer.created_at_ms > t.created_at_ms
           OR (newer.created_at_ms = t.created_at_ms AND newer.id > t.id))
WHERE newer.id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM `cw_ticket_event` e
      WHERE e.tenant_id = h.tenant_id AND e.ticket_id = t.id
        AND e.event_type = 'HANDOFF_MIGRATED'
  );

DROP TEMPORARY TABLE `_v17_legacy_handoff_latest`;
