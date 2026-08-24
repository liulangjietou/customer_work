-- 命名评测集版本：内容继续引用不可变快照，本表只承载版本名与一次性审核事实。
CREATE TABLE IF NOT EXISTS `cw_eval_dataset_release` (
    `tenant_id`          VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `release_id`         VARCHAR(64) NOT NULL COMMENT '命名版本ID（应用生成UUID）',
    `eval_type`          VARCHAR(16) NOT NULL COMMENT 'INTENT/QUALITY',
    `version_name`       VARCHAR(128) NOT NULL COMMENT '租户内、类型内唯一的人类可读版本名',
    `snapshot_version_id` VARCHAR(64) NOT NULL COMMENT '不可变内容快照 cw_eval_dataset_version.version_id',
    `content_hash`       VARCHAR(64) NOT NULL COMMENT '快照内容SHA-256，跨库绑定时用于校验漂移',
    `case_count`         INT NOT NULL COMMENT '版本包含的用例数',
    `status`             VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/APPROVED/REJECTED',
    `review_comment`     VARCHAR(500) DEFAULT NULL COMMENT '审核意见',
    `created_by`         BIGINT DEFAULT NULL COMMENT '创建人',
    `reviewed_by`        BIGINT DEFAULT NULL COMMENT '审核人',
    `created_at_ms`      BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `reviewed_at_ms`     BIGINT DEFAULT NULL COMMENT '审核时间戳（毫秒）',
    PRIMARY KEY (`release_id`),
    UNIQUE KEY `uk_eval_dataset_release_name` (`tenant_id`, `eval_type`, `version_name`),
    KEY `idx_eval_dataset_release_status` (`tenant_id`, `eval_type`, `status`, `created_at_ms`),
    KEY `idx_eval_dataset_release_snapshot` (`tenant_id`, `snapshot_version_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='评测数据集命名版本与审核';
