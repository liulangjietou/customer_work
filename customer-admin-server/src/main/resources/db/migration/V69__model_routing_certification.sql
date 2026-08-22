SET NAMES utf8mb4;

-- =============================================================================
-- ModelOps 第二纵向切片：显式路由策略、不可变版本、上线认证门禁
--
-- V68 由 EvalOps 保留。本迁移不改变 ai_model_config.id 的既有部署语义，也不搬运凭据；
-- 路由规则只保存部署 ID，认证记录只保存 SecretRef 版本号，不保存任何明文或密文。
-- =============================================================================

ALTER TABLE `ai_model_config`
    ADD COLUMN `certification_required` TINYINT NOT NULL DEFAULT 0
        COMMENT '0=存量兼容免认证/1=上线前必须通过认证' AFTER `lifecycle_status`;

CREATE TABLE IF NOT EXISTS `ai_model_route_policy` (
    `id`                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`             VARCHAR(64) NOT NULL COMMENT '租户ID',
    `policy_code`           VARCHAR(96) NOT NULL COMMENT '租户内稳定策略编码',
    `policy_name`           VARCHAR(128) NOT NULL COMMENT '策略名称',
    `description`           VARCHAR(500) DEFAULT NULL COMMENT '策略说明',
    `status`                VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/DISABLED',
    `current_version_id`    BIGINT DEFAULT NULL COMMENT '当前生效的不可变版本ID',
    `current_version_no`    INT DEFAULT NULL COMMENT '当前生效版本号',
    `latest_version_no`     INT NOT NULL DEFAULT 0 COMMENT '已创建的最新版本号',
    `create_by`             BIGINT DEFAULT NULL COMMENT '创建人',
    `create_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`             BIGINT DEFAULT NULL COMMENT '修改人',
    `update_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted`               TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY `uk_model_route_policy_tenant_code` (`tenant_id`, `policy_code`, `deleted`),
    KEY `idx_model_route_policy_tenant_status` (`tenant_id`, `status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型路由策略身份';

CREATE TABLE IF NOT EXISTS `ai_model_route_policy_version` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64) NOT NULL COMMENT '租户ID',
    `policy_id`         BIGINT NOT NULL COMMENT '路由策略ID',
    `version_no`        INT NOT NULL COMMENT '租户策略内单调递增版本号',
    `status`            VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/RETIRED',
    `content_hash`      CHAR(64) NOT NULL COMMENT '规则规范化内容 SHA-256',
    `change_note`       VARCHAR(500) DEFAULT NULL COMMENT '版本变更说明',
    `activated_by`      BIGINT DEFAULT NULL COMMENT '激活人',
    `activated_at`      DATETIME DEFAULT NULL COMMENT '激活时间',
    `create_by`         BIGINT DEFAULT NULL COMMENT '创建人',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_model_route_policy_version` (`tenant_id`, `policy_id`, `version_no`),
    KEY `idx_model_route_version_policy_status` (`tenant_id`, `policy_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型路由策略不可变版本';

CREATE TABLE IF NOT EXISTS `ai_model_route_rule` (
    `id`                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`             VARCHAR(64) NOT NULL COMMENT '租户ID',
    `policy_version_id`     BIGINT NOT NULL COMMENT '不可变策略版本ID',
    `purpose`               VARCHAR(32) NOT NULL COMMENT 'DEFAULT/ECONOMY/COMPLEX_REASONING/FALLBACK',
    `deployment_id`         BIGINT NOT NULL COMMENT 'ai_model_config.id，仅引用部署，不复制凭据',
    `priority`              INT NOT NULL COMMENT '数值越小优先级越高',
    `condition_json`        TEXT NOT NULL COMMENT '类型化条件 JSON；空对象表示无条件',
    `condition_summary`     VARCHAR(500) NOT NULL COMMENT '供审计和命中解释的条件摘要',
    `create_by`             BIGINT DEFAULT NULL COMMENT '创建人',
    `create_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY `idx_model_route_rule_version_priority` (`tenant_id`, `policy_version_id`, `priority`),
    KEY `idx_model_route_rule_deployment` (`tenant_id`, `deployment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型路由版本规则';

CREATE TABLE IF NOT EXISTS `ai_model_certification_run` (
    `id`                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`                 VARCHAR(64) NOT NULL COMMENT '租户ID',
    `model_config_id`           BIGINT NOT NULL COMMENT '模型部署ID',
    `status`                    VARCHAR(16) NOT NULL COMMENT 'PASSED/FAILED',
    `endpoint_revision`         INT NOT NULL COMMENT '认证时端点修订号',
    `secret_version`            INT DEFAULT NULL COMMENT '认证时 SecretRef 版本；不保存凭据',
    `required_context_tokens`   INT NOT NULL COMMENT '要求的上下文窗口 token 数',
    `max_latency_ms`            BIGINT NOT NULL COMMENT '基础延迟门槛',
    `max_input_price`           DECIMAL(16,6) NOT NULL COMMENT '输入单价上限/百万 token',
    `max_output_price`          DECIMAL(16,6) NOT NULL COMMENT '输出单价上限/百万 token',
    `latency_p95_ms`            BIGINT DEFAULT NULL COMMENT '基础探测 P95 延迟',
    `verified_context_tokens`   INT DEFAULT NULL COMMENT '运行时与资产声明共同确认的窗口',
    `input_price`               DECIMAL(16,6) DEFAULT NULL COMMENT '认证时生效输入单价',
    `output_price`              DECIMAL(16,6) DEFAULT NULL COMMENT '认证时生效输出单价',
    `currency`                  VARCHAR(8) DEFAULT NULL COMMENT '单价币种',
    `checks_json`               MEDIUMTEXT NOT NULL COMMENT '完整检查项与证据 JSON，不含凭据',
    `failure_code`              VARCHAR(64) DEFAULT NULL COMMENT '首个失败检查编码',
    `failure_message`           VARCHAR(500) DEFAULT NULL COMMENT '脱敏失败摘要',
    `triggered_by`              BIGINT DEFAULT NULL COMMENT '触发人',
    `started_at`                DATETIME NOT NULL COMMENT '开始时间',
    `completed_at`              DATETIME NOT NULL COMMENT '完成时间',
    `valid_until`               DATETIME DEFAULT NULL COMMENT 'PASSED 认证有效期',
    `create_time`               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    KEY `idx_model_cert_run_tenant_model` (`tenant_id`, `model_config_id`, `completed_at` DESC),
    KEY `idx_model_cert_run_status` (`tenant_id`, `status`, `valid_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署认证不可变运行记录';

CREATE TABLE IF NOT EXISTS `ai_model_certification` (
    `model_config_id`               BIGINT NOT NULL PRIMARY KEY COMMENT '模型部署ID',
    `tenant_id`                     VARCHAR(64) NOT NULL COMMENT '租户ID',
    `status`                        VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
    `current_run_id`                BIGINT DEFAULT NULL COMMENT '当前认证运行ID',
    `certified_endpoint_revision`   INT DEFAULT NULL COMMENT '通过认证的端点修订号',
    `certified_secret_version`      INT DEFAULT NULL COMMENT '通过认证的 SecretRef 版本',
    `valid_until`                   DATETIME DEFAULT NULL COMMENT '认证到期时间',
    `completed_at`                  DATETIME DEFAULT NULL COMMENT '最近完成时间',
    `passed_checks`                 INT NOT NULL DEFAULT 0 COMMENT '通过检查数',
    `failed_checks`                 INT NOT NULL DEFAULT 0 COMMENT '失败检查数',
    `latency_p95_ms`                BIGINT DEFAULT NULL COMMENT '最近基础 P95 延迟',
    `verified_context_tokens`       INT DEFAULT NULL COMMENT '最近确认上下文窗口',
    `failure_code`                  VARCHAR(64) DEFAULT NULL COMMENT '最近失败编码',
    `failure_message`               VARCHAR(500) DEFAULT NULL COMMENT '最近脱敏失败摘要',
    `revision`                      INT NOT NULL DEFAULT 1 COMMENT '快照修订号',
    `create_time`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    KEY `idx_model_certification_tenant_status` (`tenant_id`, `status`, `valid_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署认证快照';

-- 认证会真实调用外部模型并产生费用，不能复用普通编辑权限。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 20, '模型上线认证', 'model:certify', 2, 6
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'model:certify');
