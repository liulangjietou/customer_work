-- 统一客服端业务库（cw_* 表）全部业务表的排序规则为 utf8mb4_unicode_ci。
--
-- 【为什么要做】MySQL 8 下 `DEFAULT CHARSET=utf8mb4` 不带 COLLATE 时，用的是**字符集默认**
-- 即 utf8mb4_0900_ai_ci，而不是建库时 CREATE DATABASE 指定的排序规则。只有连 CHARSET 都不写的
-- 建表语句才继承建库参数。
-- 迁移文件里三种写法并存，导致同库内表间排序规则不一致，跨表比较字符串列时报
-- 1267 Illegal mix of collations。此前 V58、V97 只按需修了涉事的表，
-- ModelImpactMapper 用 COLLATE 字面量、BusinessOutcomeMapper 用 CAST(x AS BINARY) 各自绕过一次——
-- 后者的代价是 session_id 索引失效。本迁移一次性收口，让"下一张表该用什么"有唯一答案。
--
-- 【逐表守卫】已是 utf8mb4_unicode_ci 的表直接跳过，不做无谓重建。因此实际重建的表集合取决于
-- 目标库当前状态：从完整镜像初始化的库多数表已合规，本迁移接近空跑。
-- 执行前用这条 SQL 确认本次会重建哪些表、各多大：
--
--   SELECT t.TABLE_NAME, t.TABLE_COLLATION, t.TABLE_ROWS,
--          ROUND((t.DATA_LENGTH + t.INDEX_LENGTH) / 1024 / 1024) AS SIZE_MB
--     FROM information_schema.TABLES t
--    WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'
--      AND t.TABLE_NAME LIKE 'cw\_%'
--      AND t.TABLE_COLLATION <> 'utf8mb4_unicode_ci'
--    ORDER BY (t.DATA_LENGTH + t.INDEX_LENGTH) DESC;
--
-- 【锁与窗口 · 必读】实测 MySQL 8.0：
--   ALTER ... CONVERT TO ..., ALGORITHM=INPLACE  -> ERROR 1846 Cannot change column type INPLACE
--   ALTER ... CONVERT TO ..., ALGORITHM=COPY, LOCK=NONE -> ERROR 1846 COPY algorithm requires a lock
-- 即每张表都是**全表重建 + LOCK=SHARED**（读不阻塞，写阻塞），没有原生在线选项。
-- 下列高写入表如果数据量大，请安排停写窗口，或改用 gh-ost / pt-online-schema-change 单独处理
-- 后再执行本迁移（届时守卫会跳过它们）：
--     cw_agent_call_log         每次智能体调用一行
--     cw_agent_call_segment     每次模型调用一行，量级高于 call_log
--     cw_chat_message           每条聊天消息一行
--     cw_audit_log              合规审计轨迹，只增不删
--     cw_fact_log               L3 事实日志，只增不删
--     cw_outbox_message         事务外发件箱，高频写入+清理
--     cw_sensitive_word_hit_log 敏感词命中日志
--     cw_semantic_cache         语义缓存条目
--     cw_ticket_event           工单事件流
-- 其余表多为配置/低频表，通常可直接执行。
--
-- 注：字符集不变（都是 utf8mb4），CONVERT TO 只改排序规则，不重新编码列数据与中文注释。

SET @v22_cw_agent_call_log = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_agent_call_log');
SET @v22_cw_agent_call_log_sql = IF(@v22_cw_agent_call_log IS NULL OR @v22_cw_agent_call_log = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_agent_call_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_agent_call_log_stmt FROM @v22_cw_agent_call_log_sql; EXECUTE v22_cw_agent_call_log_stmt; DEALLOCATE PREPARE v22_cw_agent_call_log_stmt;

SET @v22_cw_agent_call_segment = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_agent_call_segment');
SET @v22_cw_agent_call_segment_sql = IF(@v22_cw_agent_call_segment IS NULL OR @v22_cw_agent_call_segment = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_agent_call_segment` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_agent_call_segment_stmt FROM @v22_cw_agent_call_segment_sql; EXECUTE v22_cw_agent_call_segment_stmt; DEALLOCATE PREPARE v22_cw_agent_call_segment_stmt;

SET @v22_cw_approval = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_approval');
SET @v22_cw_approval_sql = IF(@v22_cw_approval IS NULL OR @v22_cw_approval = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_approval` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_approval_stmt FROM @v22_cw_approval_sql; EXECUTE v22_cw_approval_stmt; DEALLOCATE PREPARE v22_cw_approval_stmt;

SET @v22_cw_audit_log = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_audit_log');
SET @v22_cw_audit_log_sql = IF(@v22_cw_audit_log IS NULL OR @v22_cw_audit_log = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_audit_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_audit_log_stmt FROM @v22_cw_audit_log_sql; EXECUTE v22_cw_audit_log_stmt; DEALLOCATE PREPARE v22_cw_audit_log_stmt;

SET @v22_cw_badcase = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_badcase');
SET @v22_cw_badcase_sql = IF(@v22_cw_badcase IS NULL OR @v22_cw_badcase = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_badcase` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_badcase_stmt FROM @v22_cw_badcase_sql; EXECUTE v22_cw_badcase_stmt; DEALLOCATE PREPARE v22_cw_badcase_stmt;

SET @v22_cw_chat_attachment = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_chat_attachment');
SET @v22_cw_chat_attachment_sql = IF(@v22_cw_chat_attachment IS NULL OR @v22_cw_chat_attachment = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_chat_attachment` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_chat_attachment_stmt FROM @v22_cw_chat_attachment_sql; EXECUTE v22_cw_chat_attachment_stmt; DEALLOCATE PREPARE v22_cw_chat_attachment_stmt;

SET @v22_cw_chat_message = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_chat_message');
SET @v22_cw_chat_message_sql = IF(@v22_cw_chat_message IS NULL OR @v22_cw_chat_message = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_chat_message` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_chat_message_stmt FROM @v22_cw_chat_message_sql; EXECUTE v22_cw_chat_message_stmt; DEALLOCATE PREPARE v22_cw_chat_message_stmt;

SET @v22_cw_complaint = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_complaint');
SET @v22_cw_complaint_sql = IF(@v22_cw_complaint IS NULL OR @v22_cw_complaint = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_complaint` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_complaint_stmt FROM @v22_cw_complaint_sql; EXECUTE v22_cw_complaint_stmt; DEALLOCATE PREPARE v22_cw_complaint_stmt;

SET @v22_cw_csat_survey = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_csat_survey');
SET @v22_cw_csat_survey_sql = IF(@v22_cw_csat_survey IS NULL OR @v22_cw_csat_survey = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_csat_survey` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_csat_survey_stmt FROM @v22_cw_csat_survey_sql; EXECUTE v22_cw_csat_survey_stmt; DEALLOCATE PREPARE v22_cw_csat_survey_stmt;

SET @v22_cw_dead_letter = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_dead_letter');
SET @v22_cw_dead_letter_sql = IF(@v22_cw_dead_letter IS NULL OR @v22_cw_dead_letter = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_dead_letter` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_dead_letter_stmt FROM @v22_cw_dead_letter_sql; EXECUTE v22_cw_dead_letter_stmt; DEALLOCATE PREPARE v22_cw_dead_letter_stmt;

SET @v22_cw_dialog_stage = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_dialog_stage');
SET @v22_cw_dialog_stage_sql = IF(@v22_cw_dialog_stage IS NULL OR @v22_cw_dialog_stage = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_dialog_stage` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_dialog_stage_stmt FROM @v22_cw_dialog_stage_sql; EXECUTE v22_cw_dialog_stage_stmt; DEALLOCATE PREPARE v22_cw_dialog_stage_stmt;

SET @v22_cw_dict_item = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_dict_item');
SET @v22_cw_dict_item_sql = IF(@v22_cw_dict_item IS NULL OR @v22_cw_dict_item = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_dict_item` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_dict_item_stmt FROM @v22_cw_dict_item_sql; EXECUTE v22_cw_dict_item_stmt; DEALLOCATE PREPARE v22_cw_dict_item_stmt;

SET @v22_cw_dict_type = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_dict_type');
SET @v22_cw_dict_type_sql = IF(@v22_cw_dict_type IS NULL OR @v22_cw_dict_type = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_dict_type` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_dict_type_stmt FROM @v22_cw_dict_type_sql; EXECUTE v22_cw_dict_type_stmt; DEALLOCATE PREPARE v22_cw_dict_type_stmt;

SET @v22_cw_eval_case = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_eval_case');
SET @v22_cw_eval_case_sql = IF(@v22_cw_eval_case IS NULL OR @v22_cw_eval_case = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_eval_case` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_eval_case_stmt FROM @v22_cw_eval_case_sql; EXECUTE v22_cw_eval_case_stmt; DEALLOCATE PREPARE v22_cw_eval_case_stmt;

SET @v22_cw_eval_dataset_release = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_eval_dataset_release');
SET @v22_cw_eval_dataset_release_sql = IF(@v22_cw_eval_dataset_release IS NULL OR @v22_cw_eval_dataset_release = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_eval_dataset_release` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_eval_dataset_release_stmt FROM @v22_cw_eval_dataset_release_sql; EXECUTE v22_cw_eval_dataset_release_stmt; DEALLOCATE PREPARE v22_cw_eval_dataset_release_stmt;

SET @v22_cw_eval_dataset_version = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_eval_dataset_version');
SET @v22_cw_eval_dataset_version_sql = IF(@v22_cw_eval_dataset_version IS NULL OR @v22_cw_eval_dataset_version = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_eval_dataset_version` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_eval_dataset_version_stmt FROM @v22_cw_eval_dataset_version_sql; EXECUTE v22_cw_eval_dataset_version_stmt; DEALLOCATE PREPARE v22_cw_eval_dataset_version_stmt;

SET @v22_cw_eval_run = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_eval_run');
SET @v22_cw_eval_run_sql = IF(@v22_cw_eval_run IS NULL OR @v22_cw_eval_run = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_eval_run` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_eval_run_stmt FROM @v22_cw_eval_run_sql; EXECUTE v22_cw_eval_run_stmt; DEALLOCATE PREPARE v22_cw_eval_run_stmt;

SET @v22_cw_fact_log = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_fact_log');
SET @v22_cw_fact_log_sql = IF(@v22_cw_fact_log IS NULL OR @v22_cw_fact_log = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_fact_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_fact_log_stmt FROM @v22_cw_fact_log_sql; EXECUTE v22_cw_fact_log_stmt; DEALLOCATE PREPARE v22_cw_fact_log_stmt;

SET @v22_cw_handoff_ticket = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_handoff_ticket');
SET @v22_cw_handoff_ticket_sql = IF(@v22_cw_handoff_ticket IS NULL OR @v22_cw_handoff_ticket = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_handoff_ticket` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_handoff_ticket_stmt FROM @v22_cw_handoff_ticket_sql; EXECUTE v22_cw_handoff_ticket_stmt; DEALLOCATE PREPARE v22_cw_handoff_ticket_stmt;

SET @v22_cw_harness_memory = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_harness_memory');
SET @v22_cw_harness_memory_sql = IF(@v22_cw_harness_memory IS NULL OR @v22_cw_harness_memory = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_harness_memory` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_harness_memory_stmt FROM @v22_cw_harness_memory_sql; EXECUTE v22_cw_harness_memory_stmt; DEALLOCATE PREPARE v22_cw_harness_memory_stmt;

SET @v22_cw_invoice_request = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_invoice_request');
SET @v22_cw_invoice_request_sql = IF(@v22_cw_invoice_request IS NULL OR @v22_cw_invoice_request = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_invoice_request` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_invoice_request_stmt FROM @v22_cw_invoice_request_sql; EXECUTE v22_cw_invoice_request_stmt; DEALLOCATE PREPARE v22_cw_invoice_request_stmt;

SET @v22_cw_knowledge = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_knowledge');
SET @v22_cw_knowledge_sql = IF(@v22_cw_knowledge IS NULL OR @v22_cw_knowledge = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_knowledge` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_knowledge_stmt FROM @v22_cw_knowledge_sql; EXECUTE v22_cw_knowledge_stmt; DEALLOCATE PREPARE v22_cw_knowledge_stmt;

SET @v22_cw_knowledge_gap = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_knowledge_gap');
SET @v22_cw_knowledge_gap_sql = IF(@v22_cw_knowledge_gap IS NULL OR @v22_cw_knowledge_gap = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_knowledge_gap` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_knowledge_gap_stmt FROM @v22_cw_knowledge_gap_sql; EXECUTE v22_cw_knowledge_gap_stmt; DEALLOCATE PREPARE v22_cw_knowledge_gap_stmt;

SET @v22_cw_long_term_memory = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_long_term_memory');
SET @v22_cw_long_term_memory_sql = IF(@v22_cw_long_term_memory IS NULL OR @v22_cw_long_term_memory = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_long_term_memory` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_long_term_memory_stmt FROM @v22_cw_long_term_memory_sql; EXECUTE v22_cw_long_term_memory_stmt; DEALLOCATE PREPARE v22_cw_long_term_memory_stmt;

SET @v22_cw_member = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_member');
SET @v22_cw_member_sql = IF(@v22_cw_member IS NULL OR @v22_cw_member = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_member` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_member_stmt FROM @v22_cw_member_sql; EXECUTE v22_cw_member_stmt; DEALLOCATE PREPARE v22_cw_member_stmt;

SET @v22_cw_member_account_log = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_member_account_log');
SET @v22_cw_member_account_log_sql = IF(@v22_cw_member_account_log IS NULL OR @v22_cw_member_account_log = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_member_account_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_member_account_log_stmt FROM @v22_cw_member_account_log_sql; EXECUTE v22_cw_member_account_log_stmt; DEALLOCATE PREPARE v22_cw_member_account_log_stmt;

SET @v22_cw_memory_consent = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_memory_consent');
SET @v22_cw_memory_consent_sql = IF(@v22_cw_memory_consent IS NULL OR @v22_cw_memory_consent = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_memory_consent` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_memory_consent_stmt FROM @v22_cw_memory_consent_sql; EXECUTE v22_cw_memory_consent_stmt; DEALLOCATE PREPARE v22_cw_memory_consent_stmt;

SET @v22_cw_message_feedback = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_message_feedback');
SET @v22_cw_message_feedback_sql = IF(@v22_cw_message_feedback IS NULL OR @v22_cw_message_feedback = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_message_feedback` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_message_feedback_stmt FROM @v22_cw_message_feedback_sql; EXECUTE v22_cw_message_feedback_stmt; DEALLOCATE PREPARE v22_cw_message_feedback_stmt;

SET @v22_cw_order = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_order');
SET @v22_cw_order_sql = IF(@v22_cw_order IS NULL OR @v22_cw_order = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_order` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_order_stmt FROM @v22_cw_order_sql; EXECUTE v22_cw_order_stmt; DEALLOCATE PREPARE v22_cw_order_stmt;

SET @v22_cw_outbox_message = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_outbox_message');
SET @v22_cw_outbox_message_sql = IF(@v22_cw_outbox_message IS NULL OR @v22_cw_outbox_message = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_outbox_message` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_outbox_message_stmt FROM @v22_cw_outbox_message_sql; EXECUTE v22_cw_outbox_message_stmt; DEALLOCATE PREPARE v22_cw_outbox_message_stmt;

SET @v22_cw_product = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_product');
SET @v22_cw_product_sql = IF(@v22_cw_product IS NULL OR @v22_cw_product = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_product` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_product_stmt FROM @v22_cw_product_sql; EXECUTE v22_cw_product_stmt; DEALLOCATE PREPARE v22_cw_product_stmt;

SET @v22_cw_prompt_version = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_prompt_version');
SET @v22_cw_prompt_version_sql = IF(@v22_cw_prompt_version IS NULL OR @v22_cw_prompt_version = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_prompt_version` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_prompt_version_stmt FROM @v22_cw_prompt_version_sql; EXECUTE v22_cw_prompt_version_stmt; DEALLOCATE PREPARE v22_cw_prompt_version_stmt;

SET @v22_cw_rate_limit_rule = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_rate_limit_rule');
SET @v22_cw_rate_limit_rule_sql = IF(@v22_cw_rate_limit_rule IS NULL OR @v22_cw_rate_limit_rule = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_rate_limit_rule` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_rate_limit_rule_stmt FROM @v22_cw_rate_limit_rule_sql; EXECUTE v22_cw_rate_limit_rule_stmt; DEALLOCATE PREPARE v22_cw_rate_limit_rule_stmt;

SET @v22_cw_refund = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_refund');
SET @v22_cw_refund_sql = IF(@v22_cw_refund IS NULL OR @v22_cw_refund = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_refund` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_refund_stmt FROM @v22_cw_refund_sql; EXECUTE v22_cw_refund_stmt; DEALLOCATE PREPARE v22_cw_refund_stmt;

SET @v22_cw_seat_agent = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_seat_agent');
SET @v22_cw_seat_agent_sql = IF(@v22_cw_seat_agent IS NULL OR @v22_cw_seat_agent = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_seat_agent` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_seat_agent_stmt FROM @v22_cw_seat_agent_sql; EXECUTE v22_cw_seat_agent_stmt; DEALLOCATE PREPARE v22_cw_seat_agent_stmt;

SET @v22_cw_semantic_cache = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_semantic_cache');
SET @v22_cw_semantic_cache_sql = IF(@v22_cw_semantic_cache IS NULL OR @v22_cw_semantic_cache = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_semantic_cache` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_semantic_cache_stmt FROM @v22_cw_semantic_cache_sql; EXECUTE v22_cw_semantic_cache_stmt; DEALLOCATE PREPARE v22_cw_semantic_cache_stmt;

SET @v22_cw_sensitive_word = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_sensitive_word');
SET @v22_cw_sensitive_word_sql = IF(@v22_cw_sensitive_word IS NULL OR @v22_cw_sensitive_word = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_sensitive_word` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_sensitive_word_stmt FROM @v22_cw_sensitive_word_sql; EXECUTE v22_cw_sensitive_word_stmt; DEALLOCATE PREPARE v22_cw_sensitive_word_stmt;

SET @v22_cw_sensitive_word_hit_log = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_sensitive_word_hit_log');
SET @v22_cw_sensitive_word_hit_log_sql = IF(@v22_cw_sensitive_word_hit_log IS NULL OR @v22_cw_sensitive_word_hit_log = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_sensitive_word_hit_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_sensitive_word_hit_log_stmt FROM @v22_cw_sensitive_word_hit_log_sql; EXECUTE v22_cw_sensitive_word_hit_log_stmt; DEALLOCATE PREPARE v22_cw_sensitive_word_hit_log_stmt;

SET @v22_cw_skill = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_skill');
SET @v22_cw_skill_sql = IF(@v22_cw_skill IS NULL OR @v22_cw_skill = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_skill` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_skill_stmt FROM @v22_cw_skill_sql; EXECUTE v22_cw_skill_stmt; DEALLOCATE PREPARE v22_cw_skill_stmt;

SET @v22_cw_skill_file = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_skill_file');
SET @v22_cw_skill_file_sql = IF(@v22_cw_skill_file IS NULL OR @v22_cw_skill_file = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_skill_file` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_skill_file_stmt FROM @v22_cw_skill_file_sql; EXECUTE v22_cw_skill_file_stmt; DEALLOCATE PREPARE v22_cw_skill_file_stmt;

SET @v22_cw_slot_filling_progress = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_slot_filling_progress');
SET @v22_cw_slot_filling_progress_sql = IF(@v22_cw_slot_filling_progress IS NULL OR @v22_cw_slot_filling_progress = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_slot_filling_progress` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_slot_filling_progress_stmt FROM @v22_cw_slot_filling_progress_sql; EXECUTE v22_cw_slot_filling_progress_stmt; DEALLOCATE PREPARE v22_cw_slot_filling_progress_stmt;

SET @v22_cw_subject_quota_hit = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_subject_quota_hit');
SET @v22_cw_subject_quota_hit_sql = IF(@v22_cw_subject_quota_hit IS NULL OR @v22_cw_subject_quota_hit = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_subject_quota_hit` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_subject_quota_hit_stmt FROM @v22_cw_subject_quota_hit_sql; EXECUTE v22_cw_subject_quota_hit_stmt; DEALLOCATE PREPARE v22_cw_subject_quota_hit_stmt;

SET @v22_cw_subject_quota_level = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_subject_quota_level');
SET @v22_cw_subject_quota_level_sql = IF(@v22_cw_subject_quota_level IS NULL OR @v22_cw_subject_quota_level = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_subject_quota_level` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_subject_quota_level_stmt FROM @v22_cw_subject_quota_level_sql; EXECUTE v22_cw_subject_quota_level_stmt; DEALLOCATE PREPARE v22_cw_subject_quota_level_stmt;

SET @v22_cw_tenant_quota = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_tenant_quota');
SET @v22_cw_tenant_quota_sql = IF(@v22_cw_tenant_quota IS NULL OR @v22_cw_tenant_quota = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_tenant_quota` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_tenant_quota_stmt FROM @v22_cw_tenant_quota_sql; EXECUTE v22_cw_tenant_quota_stmt; DEALLOCATE PREPARE v22_cw_tenant_quota_stmt;

SET @v22_cw_ticket = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_ticket');
SET @v22_cw_ticket_sql = IF(@v22_cw_ticket IS NULL OR @v22_cw_ticket = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_ticket` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_ticket_stmt FROM @v22_cw_ticket_sql; EXECUTE v22_cw_ticket_stmt; DEALLOCATE PREPARE v22_cw_ticket_stmt;

SET @v22_cw_ticket_event = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_ticket_event');
SET @v22_cw_ticket_event_sql = IF(@v22_cw_ticket_event IS NULL OR @v22_cw_ticket_event = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_ticket_event` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_ticket_event_stmt FROM @v22_cw_ticket_event_sql; EXECUTE v22_cw_ticket_event_stmt; DEALLOCATE PREPARE v22_cw_ticket_event_stmt;

SET @v22_cw_user = (SELECT `TABLE_COLLATION` FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE' AND `TABLE_NAME` = 'cw_user');
SET @v22_cw_user_sql = IF(@v22_cw_user IS NULL OR @v22_cw_user = 'utf8mb4_unicode_ci', 'SELECT 1',
    'ALTER TABLE `cw_user` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
PREPARE v22_cw_user_stmt FROM @v22_cw_user_sql; EXECUTE v22_cw_user_stmt; DEALLOCATE PREPARE v22_cw_user_stmt;

-- 收尾自检：逐表守卫可能因权限或锁失败而静默跳过，这里让残留直接炸掉迁移。
SET @v22_remaining = (SELECT COUNT(*) FROM `information_schema`.`TABLES`
    WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_TYPE` = 'BASE TABLE'
      AND `TABLE_NAME` IN ('cw_agent_call_log', 'cw_agent_call_segment', 'cw_approval', 'cw_audit_log', 'cw_badcase', 'cw_chat_attachment', 'cw_chat_message', 'cw_complaint', 'cw_csat_survey', 'cw_dead_letter', 'cw_dialog_stage', 'cw_dict_item', 'cw_dict_type', 'cw_eval_case', 'cw_eval_dataset_release', 'cw_eval_dataset_version', 'cw_eval_run', 'cw_fact_log', 'cw_handoff_ticket', 'cw_harness_memory', 'cw_invoice_request', 'cw_knowledge', 'cw_knowledge_gap', 'cw_long_term_memory', 'cw_member', 'cw_member_account_log', 'cw_memory_consent', 'cw_message_feedback', 'cw_order', 'cw_outbox_message', 'cw_product', 'cw_prompt_version', 'cw_rate_limit_rule', 'cw_refund', 'cw_seat_agent', 'cw_semantic_cache', 'cw_sensitive_word', 'cw_sensitive_word_hit_log', 'cw_skill', 'cw_skill_file', 'cw_slot_filling_progress', 'cw_subject_quota_hit', 'cw_subject_quota_level', 'cw_tenant_quota', 'cw_ticket', 'cw_ticket_event', 'cw_user')
      AND `TABLE_COLLATION` <> 'utf8mb4_unicode_ci');
SET @v22_verify_sql = IF(@v22_remaining = 0, 'SELECT 1',
    'SELECT * FROM `__customer_work_v22_collation_not_aligned__`');
PREPARE v22_verify_stmt FROM @v22_verify_sql;
EXECUTE v22_verify_stmt;
DEALLOCATE PREPARE v22_verify_stmt;
