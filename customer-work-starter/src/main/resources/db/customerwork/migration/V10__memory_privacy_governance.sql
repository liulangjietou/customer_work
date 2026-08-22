-- 长期记忆主体同意：原始主体字段只保留在本地控制库，L2/L3 与外部 Provider 使用 scope_id 哈希。
CREATE TABLE IF NOT EXISTS `cw_memory_consent` (
    `tenant_id`        VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `subject_type`     VARCHAR(32) NOT NULL COMMENT '主体类型: USER/SESSION/SERVICE_ACCOUNT',
    `subject_id`       VARCHAR(128) NOT NULL COMMENT '租户内主体ID',
    `agent_id`         VARCHAR(128) NOT NULL COMMENT 'Agent稳定标识',
    `scope_id`         VARCHAR(68) NOT NULL COMMENT '四维主体键SHA-256分区',
    `status`           VARCHAR(16) NOT NULL COMMENT 'GRANTED/WITHDRAWN',
    `consent_version`  VARCHAR(64) NOT NULL COMMENT '用户同意的隐私条款版本',
    `granted_at_ms`    BIGINT NULL COMMENT '授权时间戳（毫秒）',
    `withdrawn_at_ms`  BIGINT NULL COMMENT '撤回时间戳（毫秒）',
    `updated_at_ms`    BIGINT NOT NULL COMMENT '最后更新时间戳（毫秒）',
    `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                         ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
    UNIQUE KEY `uk_memory_consent_subject`
        (`tenant_id`, `subject_type`, `subject_id`, `agent_id`),
    UNIQUE KEY `uk_memory_consent_scope` (`tenant_id`, `scope_id`),
    INDEX `idx_memory_consent_status` (`tenant_id`, `status`, `updated_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长期记忆主体同意记录';
