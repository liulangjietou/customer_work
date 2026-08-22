-- Admin 工作台调用日志与客服端共用同一在线实验曝光归因契约。
ALTER TABLE `cw_agent_call_log`
    ADD COLUMN `experiment_id` BIGINT DEFAULT NULL COMMENT '在线实验ID' AFTER `version_binding_json`,
    ADD COLUMN `experiment_revision` INT DEFAULT NULL COMMENT '不可变实验修订号' AFTER `experiment_id`,
    ADD COLUMN `experiment_arm` VARCHAR(16) DEFAULT NULL COMMENT 'CONTROL/TREATMENT' AFTER `experiment_revision`,
    ADD COLUMN `experiment_deployment_id` BIGINT DEFAULT NULL COMMENT '实际命中的模型部署ID' AFTER `experiment_arm`,
    ADD COLUMN `experiment_bucket` INT DEFAULT NULL COMMENT '稳定分桶0..9999；无可信主体时为空' AFTER `experiment_deployment_id`,
    ADD INDEX `idx_call_experiment_arm`
        (`experiment_id`, `experiment_revision`, `experiment_arm`, `start_time`);
