-- 评测数据集内容快照：只插入，不更新；同租户/类型/内容只产生一个版本。
CREATE TABLE IF NOT EXISTS `cw_eval_dataset_version` (
    `tenant_id`      VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `version_id`     VARCHAR(64) NOT NULL COMMENT '数据集版本ID（应用生成UUID）',
    `eval_type`      VARCHAR(16) NOT NULL COMMENT 'INTENT/QUALITY',
    `content_hash`   VARCHAR(64) NOT NULL COMMENT '规范化用例JSON的SHA-256',
    `case_count`     INT NOT NULL COMMENT '快照用例数',
    `cases_json`     LONGTEXT NOT NULL COMMENT '本次实际执行的完整用例JSON',
    `created_at_ms`  BIGINT NOT NULL COMMENT '首次创建时间戳（毫秒）',
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_eval_dataset_content` (`tenant_id`, `eval_type`, `content_hash`),
    KEY `idx_eval_dataset_tenant_time` (`tenant_id`, `eval_type`, `created_at_ms`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='评测数据集不可变版本';

-- 运行记录绑定可复现版本；保留 prompt_fingerprint 兼容既有查询与历史行。
ALTER TABLE `cw_eval_run`
    ADD COLUMN `dataset_version_id` VARCHAR(64) DEFAULT NULL COMMENT '本次实际执行的数据集版本' AFTER `dataset_size`,
    ADD COLUMN `dataset_fingerprint` VARCHAR(64) DEFAULT NULL COMMENT '数据集内容SHA-256' AFTER `dataset_version_id`,
    ADD COLUMN `version_binding_json` TEXT DEFAULT NULL COMMENT '模型/提示词/Agent/知识/工具/Judge/rubric版本绑定JSON' AFTER `dataset_fingerprint`,
    ADD KEY `idx_eval_run_dataset_version` (`tenant_id`, `eval_type`, `dataset_version_id`);
