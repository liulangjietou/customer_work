-- 将客服端库遗留的平台租户数据归并到唯一保留租户 default。
--
-- 仅更新 tenant_id = '__platform__' 的行，真实业务租户不受影响。
-- 所有唯一键冲突均由数据库直接中止迁移；禁止 UPDATE IGNORE、REPLACE 或静默删除冲突行。

-- MySQL 的多条 DML 不具备整份迁移级原子性，必须在任何业务写入前完成冲突预检。
-- 临时表先放入固定值 1；13 类租户唯一键任一命中时再次插入 1，由唯一约束立即中止迁移。
CREATE TEMPORARY TABLE `_v7_platform_tenant_conflict_guard` (
    `singleton` TINYINT NOT NULL,
    UNIQUE KEY `uk_v7_platform_tenant_conflict_guard` (`singleton`)
) ENGINE=InnoDB;

INSERT INTO `_v7_platform_tenant_conflict_guard` (`singleton`) VALUES (1);

INSERT INTO `_v7_platform_tenant_conflict_guard` (`singleton`)
SELECT 1
FROM (
    SELECT 1 AS `conflict`
    FROM `cw_user` p
    INNER JOIN `cw_user` d
        ON d.`tenant_id` = 'default' AND d.`username` = p.`username`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_knowledge` p
    INNER JOIN `cw_knowledge` d
        ON d.`tenant_id` = 'default' AND d.`title` = p.`title`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_sensitive_word` p
    INNER JOIN `cw_sensitive_word` d
        ON d.`tenant_id` = 'default' AND d.`word` = p.`word`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_rate_limit_rule` p
    INNER JOIN `cw_rate_limit_rule` d
        ON d.`tenant_id` = 'default' AND d.`rule_name` = p.`rule_name`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_dict_type` p
    INNER JOIN `cw_dict_type` d
        ON d.`tenant_id` = 'default' AND d.`dict_type` = p.`dict_type`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_dict_item` p
    INNER JOIN `cw_dict_item` d
        ON d.`tenant_id` = 'default'
       AND d.`dict_type` = p.`dict_type`
       AND d.`item_key` = p.`item_key`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_tenant_quota` p
    INNER JOIN `cw_tenant_quota` d
        ON d.`tenant_id` = 'default' AND d.`period` = p.`period`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_long_term_memory` p
    INNER JOIN `cw_long_term_memory` d
        ON d.`tenant_id` = 'default' AND d.`scope_hash` = p.`scope_hash`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_harness_memory` p
    INNER JOIN `cw_harness_memory` d
        ON d.`tenant_id` = 'default' AND d.`scope_hash` = p.`scope_hash`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_skill` p
    INNER JOIN `cw_skill` d
        ON d.`tenant_id` = 'default' AND d.`skill_code` = p.`skill_code`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_eval_case` p
    INNER JOIN `cw_eval_case` d
        ON d.`tenant_id` = 'default'
       AND d.`eval_type` = p.`eval_type`
       AND d.`case_id` = p.`case_id`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_knowledge_gap` p
    INNER JOIN `cw_knowledge_gap` d
        ON d.`tenant_id` = 'default'
       AND d.`scope_id` = 'default'
       AND d.`question_hash` = p.`question_hash`
    WHERE p.`tenant_id` = '__platform__'

    UNION ALL

    SELECT 1
    FROM `cw_subject_quota_level` p
    INNER JOIN `cw_subject_quota_level` d
        ON d.`tenant_id` = 'default' AND d.`level_code` = p.`level_code`
    WHERE p.`tenant_id` = '__platform__'
) conflicts
LIMIT 1;

DROP TEMPORARY TABLE `_v7_platform_tenant_conflict_guard`;

UPDATE `cw_agent_call_log`
SET `tenant_id` = 'default',
    `user_id` = CASE
        WHEN LEFT(`user_id`, CHAR_LENGTH('__platform__::')) = '__platform__::'
            THEN CONCAT('default::', SUBSTRING(`user_id`, CHAR_LENGTH('__platform__::') + 1))
        ELSE `user_id`
    END
WHERE `tenant_id` = '__platform__';
UPDATE `cw_agent_call_segment` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_approval` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_audit_log` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_badcase` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_chat_attachment` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_chat_message` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_complaint` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_csat_survey`
SET `tenant_id` = 'default', `scope_id` = 'default'
WHERE `tenant_id` = '__platform__';
UPDATE `cw_dead_letter` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_dialog_stage` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_dict_item` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_dict_type` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_eval_case` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_eval_run` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_fact_log` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_handoff_ticket` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_harness_memory` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_invoice_request` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_knowledge` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_knowledge_gap`
SET `tenant_id` = 'default', `scope_id` = 'default'
WHERE `tenant_id` = '__platform__';
UPDATE `cw_long_term_memory` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_member` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_member_account_log` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_message_feedback` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_order` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_outbox_message` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_product` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_prompt_version` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_rate_limit_rule` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_refund` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_seat_agent` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_semantic_cache` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_sensitive_word` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_sensitive_word_hit_log` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_skill` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_skill_file` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_slot_filling_progress` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_subject_quota_hit` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_subject_quota_level` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_tenant_quota` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_ticket` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_ticket_event` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
UPDATE `cw_user` SET `tenant_id` = 'default' WHERE `tenant_id` = '__platform__';
