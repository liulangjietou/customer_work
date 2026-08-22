SET NAMES utf8mb4;

-- =============================================================================
-- ModelOps 第一纵向切片：资产/部署分离、SecretRef、持续健康、影响预检
--
-- 迁移只做加法：ai_model_config.id 仍是 Agent 引用的部署 ID；api_key/test_status/test_time
-- 暂时保留给旧运行时协议。应用先双读、双写，后续版本完成运行时升级后再清理旧列。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_model_asset` (
    `id`                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`             VARCHAR(64) NOT NULL COMMENT '租户ID',
    `asset_code`            VARCHAR(96) NOT NULL COMMENT '租户内稳定资产编码',
    `asset_name`            VARCHAR(128) NOT NULL COMMENT '资产名称',
    `vendor`                VARCHAR(64) NOT NULL DEFAULT 'CUSTOM' COMMENT '模型厂商，不等同于接入协议',
    `model_key`             VARCHAR(128) NOT NULL COMMENT '厂商模型标识',
    `family`                VARCHAR(64) DEFAULT NULL COMMENT '模型家族',
    `asset_version`         VARCHAR(64) DEFAULT NULL COMMENT '模型版本',
    `modality`              VARCHAR(64) NOT NULL DEFAULT 'TEXT' COMMENT '能力模态，逗号分隔',
    `context_window`        INT DEFAULT NULL COMMENT '上下文窗口 token 数',
    `max_output_tokens`     INT DEFAULT NULL COMMENT '最大输出 token 数',
    `supports_stream`       TINYINT NOT NULL DEFAULT 1 COMMENT '是否支持流式输出',
    `supports_tool`         TINYINT NOT NULL DEFAULT 1 COMMENT '是否支持工具调用',
    `supports_json_schema`  TINYINT NOT NULL DEFAULT 0 COMMENT '是否支持 JSON Schema',
    `supports_multimodal`   TINYINT NOT NULL DEFAULT 0 COMMENT '是否支持多模态',
    `capability_hash`       VARCHAR(64) DEFAULT NULL COMMENT '能力声明摘要',
    `lifecycle_status`      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/DEPRECATED/RETIRED',
    `create_by`             BIGINT DEFAULT NULL COMMENT '创建人',
    `create_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`             BIGINT DEFAULT NULL COMMENT '修改人',
    `update_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted`               TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY `uk_model_asset_tenant_code` (`tenant_id`, `asset_code`, `deleted`),
    KEY `idx_model_asset_tenant_model` (`tenant_id`, `vendor`, `model_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型目录资产';

CREATE TABLE IF NOT EXISTS `ai_secret_ref` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64) NOT NULL COMMENT '租户ID',
    `ref_code`          VARCHAR(128) NOT NULL COMMENT '租户内稳定凭据引用编码',
    `ref_name`          VARCHAR(128) NOT NULL COMMENT '凭据名称',
    `provider_type`     VARCHAR(32) NOT NULL DEFAULT 'LOCAL_AES' COMMENT 'LOCAL_AES/VAULT/AWS_SM/AZURE_KV/GCP_SM/ENV',
    `external_ref`      VARCHAR(512) DEFAULT NULL COMMENT '外部密钥管理器引用，不保存密钥值',
    `current_version`   INT NOT NULL DEFAULT 1 COMMENT '当前版本',
    `status`            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/DISABLED/ERROR',
    `expires_at`        DATETIME DEFAULT NULL COMMENT '凭据过期时间',
    `last_rotated_at`   DATETIME DEFAULT NULL COMMENT '最近轮换时间',
    `last_rotated_by`   BIGINT DEFAULT NULL COMMENT '最近轮换人',
    `create_by`         BIGINT DEFAULT NULL COMMENT '创建人',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT DEFAULT NULL COMMENT '修改人',
    `update_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted`           TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY `uk_secret_ref_tenant_code` (`tenant_id`, `ref_code`, `deleted`),
    KEY `idx_secret_ref_tenant_status` (`tenant_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='密钥引用元数据';

CREATE TABLE IF NOT EXISTS `ai_secret_material` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64) NOT NULL COMMENT '租户ID',
    `secret_ref_id`     BIGINT NOT NULL COMMENT 'ai_secret_ref.id',
    `version`           INT NOT NULL COMMENT '不可变密钥版本',
    `cipher_text`       VARCHAR(2048) NOT NULL COMMENT 'LOCAL_AES 密文，禁止通过接口返回',
    `key_id`            VARCHAR(128) NOT NULL DEFAULT 'admin-aes-gcm' COMMENT '加密主密钥标识',
    `status`            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUPERSEDED/REVOKED',
    `create_by`         BIGINT DEFAULT NULL COMMENT '创建人',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_secret_material_version` (`secret_ref_id`, `version`),
    KEY `idx_secret_material_tenant_ref` (`tenant_id`, `secret_ref_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地加密密钥版本';

ALTER TABLE `ai_model_config`
    ADD COLUMN `asset_id` BIGINT DEFAULT NULL COMMENT '模型目录资产ID' AFTER `tenant_id`,
    ADD COLUMN `deployment_code` VARCHAR(96) DEFAULT NULL COMMENT '租户内稳定部署编码' AFTER `model_name`,
    ADD COLUMN `protocol_adapter` VARCHAR(32) DEFAULT NULL COMMENT '运行时接入协议' AFTER `provider`,
    ADD COLUMN `region` VARCHAR(64) DEFAULT NULL COMMENT '部署地域' AFTER `base_url`,
    ADD COLUMN `environment` VARCHAR(32) NOT NULL DEFAULT 'PRODUCTION' COMMENT '部署环境' AFTER `region`,
    ADD COLUMN `secret_ref_id` BIGINT DEFAULT NULL COMMENT '凭据引用ID' AFTER `api_key`,
    ADD COLUMN `endpoint_revision` INT NOT NULL DEFAULT 1 COMMENT '端点配置修订号' AFTER `environment`,
    ADD COLUMN `lifecycle_status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DEPRECATED/RETIRED' AFTER `endpoint_revision`,
    ADD KEY `idx_model_config_asset` (`tenant_id`, `asset_id`),
    ADD KEY `idx_model_config_secret` (`tenant_id`, `secret_ref_id`),
    ADD UNIQUE KEY `uk_model_deployment_tenant_code` (`tenant_id`, `deployment_code`, `deleted`);

CREATE TABLE IF NOT EXISTS `ai_model_health_snapshot` (
    `model_config_id`       BIGINT NOT NULL PRIMARY KEY COMMENT '模型部署ID',
    `tenant_id`             VARCHAR(64) NOT NULL COMMENT '租户ID',
    `health_status`         VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/HEALTHY/DEGRADED/UNHEALTHY/RECOVERING',
    `auth_status`           VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
    `capability_status`     VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
    `consecutive_failures`  INT NOT NULL DEFAULT 0 COMMENT '连续失败次数',
    `last_latency_ms`       BIGINT DEFAULT NULL COMMENT '最近探测耗时',
    `last_error_category`   VARCHAR(32) DEFAULT NULL COMMENT 'AUTH/RATE_LIMIT/TIMEOUT/CONTRACT/UNKNOWN',
    `last_message`          VARCHAR(500) DEFAULT NULL COMMENT '脱敏后的最近结果摘要',
    `last_probe_at`         DATETIME DEFAULT NULL COMMENT '最近探测时间',
    `last_success_at`       DATETIME DEFAULT NULL COMMENT '最近成功时间',
    `last_failure_at`       DATETIME DEFAULT NULL COMMENT '最近失败时间',
    `next_probe_at`         DATETIME DEFAULT NULL COMMENT '下次计划探测时间',
    `revision`              INT NOT NULL DEFAULT 1 COMMENT '快照修订号',
    `create_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    KEY `idx_model_health_tenant_status` (`tenant_id`, `health_status`, `next_probe_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署健康快照';

CREATE TABLE IF NOT EXISTS `ai_model_health_event` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64) NOT NULL COMMENT '租户ID',
    `model_config_id`   BIGINT NOT NULL COMMENT '模型部署ID',
    `source`            VARCHAR(16) NOT NULL COMMENT 'MANUAL/SCHEDULED/RUNTIME/MIGRATION',
    `probe_kind`        VARCHAR(16) NOT NULL DEFAULT 'CONNECTIVITY' COMMENT 'CONNECTIVITY/AUTH/CAPABILITY',
    `health_status`     VARCHAR(16) NOT NULL COMMENT '本次探测后的健康状态',
    `test_status`       TINYINT NOT NULL COMMENT '兼容连通性状态：0/1/2',
    `latency_ms`        BIGINT DEFAULT NULL COMMENT '探测耗时',
    `error_category`    VARCHAR(32) DEFAULT NULL COMMENT 'AUTH/RATE_LIMIT/TIMEOUT/CONTRACT/UNKNOWN',
    `message`           VARCHAR(500) DEFAULT NULL COMMENT '脱敏后的结果摘要',
    `occurred_at`       DATETIME NOT NULL COMMENT '事件时间',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    KEY `idx_model_health_event_tenant_model` (`tenant_id`, `model_config_id`, `occurred_at` DESC),
    KEY `idx_model_health_event_category` (`tenant_id`, `error_category`, `occurred_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型部署健康事件';

-- 每个存量部署生成一个独立资产；不会擅自把同名模型合并成同一资产。
INSERT INTO `ai_model_asset` (
    `tenant_id`, `asset_code`, `asset_name`, `vendor`, `model_key`, `lifecycle_status`,
    `create_by`, `create_time`, `update_by`, `update_time`, `deleted`
)
SELECT
    `tenant_id`, CONCAT('legacy-asset-', `id`), `model_name`,
    CASE `provider`
        WHEN 'dashscope' THEN 'ALIBABA'
        WHEN 'anthropic' THEN 'ANTHROPIC'
        WHEN 'gemini' THEN 'GOOGLE'
        ELSE 'CUSTOM'
    END,
    `model`, CASE WHEN `deleted` = 1 THEN 'RETIRED' ELSE 'ACTIVE' END,
    `create_by`, `create_time`, `update_by`, `update_time`, `deleted`
FROM `ai_model_config`;

UPDATE `ai_model_config` `mc`
INNER JOIN `ai_model_asset` `asset`
        ON `asset`.`tenant_id` = `mc`.`tenant_id`
       AND `asset`.`asset_code` = CONCAT('legacy-asset-', `mc`.`id`)
       AND `asset`.`deleted` = `mc`.`deleted`
SET `mc`.`asset_id` = `asset`.`id`,
    `mc`.`deployment_code` = CONCAT('deployment-', `mc`.`id`),
    `mc`.`protocol_adapter` = `mc`.`provider`;

-- 存量密文原样迁入 LOCAL_AES material，避免迁移脚本接触明文。
INSERT INTO `ai_secret_ref` (
    `tenant_id`, `ref_code`, `ref_name`, `provider_type`, `current_version`, `status`,
    `last_rotated_at`, `last_rotated_by`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`
)
SELECT
    `tenant_id`, CONCAT('model-deployment-', `id`), CONCAT(`model_name`, ' 凭据'), 'LOCAL_AES', 1,
    CASE WHEN `deleted` = 1 THEN 'DISABLED' ELSE 'ACTIVE' END,
    COALESCE(`update_time`, `create_time`), `update_by`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`
FROM `ai_model_config`;

INSERT INTO `ai_secret_material` (
    `tenant_id`, `secret_ref_id`, `version`, `cipher_text`, `key_id`, `status`, `create_by`, `create_time`
)
SELECT
    `mc`.`tenant_id`, `ref`.`id`, 1, `mc`.`api_key`, 'admin-aes-gcm',
    CASE WHEN `mc`.`deleted` = 1 THEN 'REVOKED' ELSE 'ACTIVE' END,
    `mc`.`create_by`, `mc`.`create_time`
FROM `ai_model_config` `mc`
INNER JOIN `ai_secret_ref` `ref`
        ON `ref`.`tenant_id` = `mc`.`tenant_id`
       AND `ref`.`ref_code` = CONCAT('model-deployment-', `mc`.`id`)
       AND `ref`.`deleted` = `mc`.`deleted`;

UPDATE `ai_model_config` `mc`
INNER JOIN `ai_secret_ref` `ref`
        ON `ref`.`tenant_id` = `mc`.`tenant_id`
       AND `ref`.`ref_code` = CONCAT('model-deployment-', `mc`.`id`)
       AND `ref`.`deleted` = `mc`.`deleted`
SET `mc`.`secret_ref_id` = `ref`.`id`;

-- 把旧的一次性 test_status 投影成首个健康快照和迁移事件。
INSERT INTO `ai_model_health_snapshot` (
    `model_config_id`, `tenant_id`, `health_status`, `auth_status`, `capability_status`,
    `consecutive_failures`, `last_error_category`, `last_probe_at`, `last_success_at`, `last_failure_at`
)
SELECT
    `id`, `tenant_id`,
    CASE WHEN `test_status` = 1 THEN 'HEALTHY' ELSE 'UNHEALTHY' END,
    CASE WHEN `test_status` = 1 THEN 'PASSED' ELSE 'FAILED' END,
    'UNKNOWN', CASE WHEN `test_status` = 1 THEN 0 ELSE 1 END,
    CASE WHEN `test_status` = 1 THEN NULL ELSE 'UNKNOWN' END,
    `test_time`, CASE WHEN `test_status` = 1 THEN `test_time` ELSE NULL END,
    CASE WHEN `test_status` = 2 THEN `test_time` ELSE NULL END
FROM `ai_model_config`
WHERE `test_status` IN (1, 2) AND `test_time` IS NOT NULL;

INSERT INTO `ai_model_health_event` (
    `tenant_id`, `model_config_id`, `source`, `probe_kind`, `health_status`, `test_status`,
    `error_category`, `message`, `occurred_at`
)
SELECT
    `tenant_id`, `id`, 'MIGRATION', 'CONNECTIVITY',
    CASE WHEN `test_status` = 1 THEN 'HEALTHY' ELSE 'UNHEALTHY' END,
    `test_status`, CASE WHEN `test_status` = 1 THEN NULL ELSE 'UNKNOWN' END,
    '由旧连通性状态迁移', `test_time`
FROM `ai_model_config`
WHERE `test_status` IN (1, 2) AND `test_time` IS NOT NULL;

-- 手工健康探测会真实访问外部模型并可能产生费用，不能继续复用只读权限。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 20, '模型健康探测', 'model:health-test', 2, 5
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'model:health-test');
