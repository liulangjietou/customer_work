SET NAMES utf8mb4;

-- 在线实验生命周期记录不可变发布任务，控制面据此区分期望状态与运行时真实生效状态。
ALTER TABLE `ai_model_experiment`
    ADD COLUMN `activation_task_id` VARCHAR(64) DEFAULT NULL COMMENT 'ACTIVATE可靠发布任务ID' AFTER `status`,
    ADD COLUMN `deactivation_task_id` VARCHAR(64) DEFAULT NULL COMMENT 'DEACTIVATE可靠发布任务ID' AFTER `activation_task_id`,
    ADD KEY `idx_model_experiment_activation_task` (`tenant_id`, `activation_task_id`),
    ADD KEY `idx_model_experiment_deactivation_task` (`tenant_id`, `deactivation_task_id`);

-- 发布动作在入队时固化，Worker 不得从之后可能已变化的实验状态反推 ACTIVATE/DEACTIVATE。
ALTER TABLE `ai_runtime_publish_task`
    ADD COLUMN `experiment_id` BIGINT DEFAULT NULL COMMENT '在线实验ID；通用发布任务为空' AFTER `target_id`,
    ADD COLUMN `experiment_publish_action` VARCHAR(16) DEFAULT NULL COMMENT 'ACTIVATE/DEACTIVATE；通用发布任务为空' AFTER `experiment_id`,
    ADD KEY `idx_runtime_publish_experiment` (`tenant_id`, `experiment_id`, `seq`),
    ADD CONSTRAINT `chk_runtime_publish_experiment_intent`
        CHECK (
            (`experiment_id` IS NULL AND `experiment_publish_action` IS NULL)
            OR
            (`experiment_id` IS NOT NULL
                AND `experiment_publish_action` IN ('ACTIVATE', 'DEACTIVATE'))
        );
