-- 评测数据集治理与模型实验双臂离线门禁。
-- MySQL DDL 不可事务回滚：ALTER 按实际列状态生成，CREATE/权限写入均幂等。

SET NAMES utf8mb4;

SET @v92_table_count = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
      AND table_name IN ('ai_model_experiment', 'sys_permission'));
SET @v92_preflight_sql = IF(@v92_table_count = 2, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v92_required_tables_missing__`');
PREPARE v92_preflight_stmt FROM @v92_preflight_sql;
EXECUTE v92_preflight_stmt;
DEALLOCATE PREPARE v92_preflight_stmt;

SET @v92_experiment_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'dataset_release_id'), '',
       ', ADD COLUMN `dataset_release_id` VARCHAR(64) DEFAULT NULL COMMENT ''审核通过的数据集命名版本ID'' AFTER `treatment_endpoint_revision`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'dataset_version_name'), '',
       ', ADD COLUMN `dataset_version_name` VARCHAR(128) DEFAULT NULL COMMENT ''创建时的数据集版本名快照'' AFTER `dataset_release_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'dataset_snapshot_version_id'), '',
       ', ADD COLUMN `dataset_snapshot_version_id` VARCHAR(64) DEFAULT NULL COMMENT ''不可变数据集内容快照ID'' AFTER `dataset_version_name`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'dataset_content_hash'), '',
       ', ADD COLUMN `dataset_content_hash` CHAR(64) DEFAULT NULL COMMENT ''数据集内容SHA-256'' AFTER `dataset_snapshot_version_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'judge_deployment_id'), '',
       ', ADD COLUMN `judge_deployment_id` BIGINT DEFAULT NULL COMMENT ''创建时冻结的Judge部署ID'' AFTER `dataset_content_hash`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'judge_model_ref'), '',
       ', ADD COLUMN `judge_model_ref` VARCHAR(128) DEFAULT NULL COMMENT ''创建时Judge模型标识快照'' AFTER `judge_deployment_id`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'judge_endpoint_revision'), '',
       ', ADD COLUMN `judge_endpoint_revision` INT DEFAULT NULL COMMENT ''创建时Judge端点修订号'' AFTER `judge_model_ref`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'offline_eval_status'), '',
       ', ADD COLUMN `offline_eval_status` VARCHAR(16) NOT NULL DEFAULT ''NOT_STARTED'' COMMENT ''NOT_STARTED/RUNNING/PASSED/FAILED'' AFTER `judge_endpoint_revision`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'offline_eval_started_at'), '',
       ', ADD COLUMN `offline_eval_started_at` DATETIME(6) DEFAULT NULL COMMENT ''离线评测开始时间'' AFTER `offline_eval_status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'offline_eval_completed_at'), '',
       ', ADD COLUMN `offline_eval_completed_at` DATETIME(6) DEFAULT NULL COMMENT ''离线评测完成时间'' AFTER `offline_eval_started_at`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_model_experiment' AND column_name = 'offline_eval_error'), '',
       ', ADD COLUMN `offline_eval_error` VARCHAR(500) DEFAULT NULL COMMENT ''门禁失败摘要'' AFTER `offline_eval_completed_at`')
);
SET @v92_experiment_ddl = IF(@v92_experiment_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_model_experiment` ', SUBSTRING(@v92_experiment_columns, 3)));
PREPARE v92_experiment_stmt FROM @v92_experiment_ddl;
EXECUTE v92_experiment_stmt;
DEALLOCATE PREPARE v92_experiment_stmt;

CREATE TABLE IF NOT EXISTS `ai_model_experiment_arm_eval` (
    `id`                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`                   VARCHAR(64) NOT NULL COMMENT '租户ID',
    `experiment_id`               BIGINT NOT NULL COMMENT '模型实验ID',
    `arm`                         VARCHAR(16) NOT NULL COMMENT 'CONTROL/TREATMENT',
    `attempt_no`                  INT NOT NULL COMMENT '该臂评测尝试序号',
    `deployment_id`               BIGINT NOT NULL COMMENT '被测部署ID',
    `endpoint_revision`           INT NOT NULL COMMENT '被测端点修订号',
    `dataset_release_id`          VARCHAR(64) NOT NULL COMMENT '数据集命名版本ID',
    `dataset_snapshot_version_id` VARCHAR(64) NOT NULL COMMENT '不可变数据集快照ID',
    `dataset_content_hash`        CHAR(64) NOT NULL COMMENT '数据集内容SHA-256',
    `judge_deployment_id`         BIGINT NOT NULL COMMENT 'Judge部署ID',
    `judge_endpoint_revision`     INT NOT NULL COMMENT 'Judge端点修订号',
    `rubric_version`              CHAR(64) NOT NULL COMMENT '评分rubric指纹',
    `status`                      VARCHAR(16) NOT NULL COMMENT 'RUNNING/PASSED/FAILED/ERROR',
    `total`                       INT DEFAULT NULL COMMENT '用例总数',
    `judged`                      INT DEFAULT NULL COMMENT '成功评分数',
    `passed`                      INT DEFAULT NULL COMMENT '通过用例数',
    `avg_score`                   DECIMAL(8,6) DEFAULT NULL COMMENT '平均分1到5',
    `pass_rate`                   DECIMAL(8,6) DEFAULT NULL COMMENT '通过率0到1',
    `failed_case_ids_json`        TEXT DEFAULT NULL COMMENT '低分用例ID数组',
    `error_case_ids_json`         TEXT DEFAULT NULL COMMENT '评分错误用例ID数组',
    `error_message`               VARCHAR(1000) DEFAULT NULL COMMENT '执行错误摘要',
    `started_at`                  DATETIME(6) NOT NULL COMMENT '开始时间',
    `completed_at`                DATETIME(6) DEFAULT NULL COMMENT '完成时间',
    `create_time`                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '写入时间',
    UNIQUE KEY `uk_model_experiment_arm_attempt`
        (`tenant_id`, `experiment_id`, `arm`, `attempt_no`),
    KEY `idx_model_experiment_arm_eval`
        (`tenant_id`, `experiment_id`, `attempt_no` DESC),
    CONSTRAINT `chk_model_experiment_arm` CHECK (`arm` IN ('CONTROL', 'TREATMENT')),
    CONSTRAINT `chk_model_experiment_arm_eval_status`
        CHECK (`status` IN ('RUNNING', 'PASSED', 'FAILED', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='模型实验control/treatment离线评测事实';

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 232, '编辑评测数据集', 'eval:dataset-edit', 2, 2
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'eval:dataset-edit');

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT 232, '审核评测数据集版本', 'eval:dataset-review', 2, 3
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'eval:dataset-review');
