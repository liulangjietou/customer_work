SET NAMES utf8mb4;

-- =============================================================================
-- 在线模型双臂实验控制面
--
-- 本迁移只建立实验定义、生命周期和审计事件。流量分桶与调用指标必须由运行时按
-- experiment_id/revision/salt 写入后再接入，控制面在此之前明确返回 AWAITING_RUNTIME。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_model_experiment` (
    `id`                            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`                     VARCHAR(64) NOT NULL COMMENT '租户ID',
    `experiment_code`               VARCHAR(96) NOT NULL COMMENT '租户内稳定实验编码',
    `experiment_name`               VARCHAR(128) NOT NULL COMMENT '实验名称',
    `agent_id`                      BIGINT NOT NULL COMMENT '实验智能体ID',
    `control_deployment_id`         BIGINT NOT NULL COMMENT '对照组模型部署ID',
    `control_model_ref`             VARCHAR(128) NOT NULL COMMENT '创建时对照组模型标识快照',
    `control_endpoint_revision`     INT NOT NULL COMMENT '创建时对照组端点修订号',
    `treatment_deployment_id`       BIGINT NOT NULL COMMENT '实验组模型部署ID',
    `treatment_model_ref`           VARCHAR(128) NOT NULL COMMENT '创建时实验组模型标识快照',
    `treatment_endpoint_revision`   INT NOT NULL COMMENT '创建时实验组端点修订号',
    `revision`                      INT NOT NULL DEFAULT 1 COMMENT '不可变实验修订号',
    `assignment_salt`               CHAR(32) NOT NULL COMMENT '不可变确定性分桶盐值',
    `treatment_bps`                 INT NOT NULL COMMENT '实验组流量，基点制1..9999',
    `status`                        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/STOPPED/COMPLETED',
    `min_sample`                    BIGINT NOT NULL COMMENT '触发护栏判断的最小样本数',
    `max_error_rate`                DECIMAL(8,7) NOT NULL COMMENT '错误率护栏0..1',
    `max_p95_latency_ms`            BIGINT NOT NULL COMMENT 'P95延迟护栏毫秒',
    `expires_at`                    DATETIME NOT NULL COMMENT '实验硬截止时间',
    `started_at`                    DATETIME DEFAULT NULL COMMENT '启动时间',
    `stopped_at`                    DATETIME DEFAULT NULL COMMENT '停止时间',
    `completed_at`                  DATETIME DEFAULT NULL COMMENT '正常到期完成时间',
    `stop_reason`                   VARCHAR(500) DEFAULT NULL COMMENT '停止、自动停止或到期原因',
    `create_by`                     BIGINT DEFAULT NULL COMMENT '创建人',
    `create_time`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`                     BIGINT DEFAULT NULL COMMENT '修改人',
    `update_time`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `running_agent_id`              BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` = 'RUNNING' THEN `agent_id` ELSE NULL END
    ) STORED COMMENT '仅RUNNING态参与唯一约束',
    UNIQUE KEY `uk_model_experiment_tenant_code` (`tenant_id`, `experiment_code`),
    UNIQUE KEY `uk_model_experiment_one_running_agent` (`tenant_id`, `running_agent_id`),
    KEY `idx_model_experiment_tenant_status` (`tenant_id`, `status`, `create_time` DESC),
    KEY `idx_model_experiment_expiry` (`status`, `expires_at`),
    CONSTRAINT `chk_model_experiment_treatment_bps`
        CHECK (`treatment_bps` BETWEEN 1 AND 9999),
    CONSTRAINT `chk_model_experiment_status`
        CHECK (`status` IN ('DRAFT', 'RUNNING', 'STOPPED', 'COMPLETED')),
    CONSTRAINT `chk_model_experiment_revision`
        CHECK (`revision` >= 1),
    CONSTRAINT `chk_model_experiment_min_sample`
        CHECK (`min_sample` >= 1),
    CONSTRAINT `chk_model_experiment_error_rate`
        CHECK (`max_error_rate` BETWEEN 0 AND 1),
    CONSTRAINT `chk_model_experiment_p95`
        CHECK (`max_p95_latency_ms` >= 1),
    CONSTRAINT `chk_model_experiment_distinct_arms`
        CHECK (`control_deployment_id` <> `treatment_deployment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='在线模型双臂实验定义';

CREATE TABLE IF NOT EXISTS `ai_model_experiment_event` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64) NOT NULL COMMENT '租户ID',
    `experiment_id`     BIGINT NOT NULL COMMENT '实验ID',
    `event_type`        VARCHAR(16) NOT NULL COMMENT 'START/STOP/AUTO_STOP/EXPIRED',
    `from_status`       VARCHAR(16) NOT NULL COMMENT '变更前状态',
    `to_status`         VARCHAR(16) NOT NULL COMMENT '变更后状态',
    `reason`            VARCHAR(500) DEFAULT NULL COMMENT '停止或系统判断原因',
    `actor_id`          BIGINT DEFAULT NULL COMMENT '人工操作人；系统事件为空',
    `occurred_at`       DATETIME NOT NULL COMMENT '事件发生时间',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    KEY `idx_model_experiment_event_tenant_experiment`
        (`tenant_id`, `experiment_id`, `occurred_at` DESC),
    CONSTRAINT `chk_model_experiment_event_type`
        CHECK (`event_type` IN ('START', 'STOP', 'AUTO_STOP', 'EXPIRED')),
    CONSTRAINT `chk_model_experiment_event_from_status`
        CHECK (`from_status` IN ('DRAFT', 'RUNNING', 'STOPPED', 'COMPLETED')),
    CONSTRAINT `chk_model_experiment_event_to_status`
        CHECK (`to_status` IN ('DRAFT', 'RUNNING', 'STOPPED', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='在线模型实验追加式生命周期事件';

-- 最大显式权限 ID 截至 V71 前为 248；此处沿用自增插入，避免与并行迁移争抢固定 ID。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 20, '查看模型实验', 'model-experiment:view', 2, 7
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'model-experiment:view');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 20, '创建模型实验', 'model-experiment:create', 2, 8
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'model-experiment:create');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 20, '启动模型实验', 'model-experiment:start', 2, 9
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'model-experiment:start');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 20, '停止模型实验', 'model-experiment:stop', 2, 10
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'model-experiment:stop');
