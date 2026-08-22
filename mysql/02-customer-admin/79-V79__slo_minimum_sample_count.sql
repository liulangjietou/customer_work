-- SLO 低样本保护：短、长窗口都达到门槛后才允许产生燃烧告警。
SET NAMES utf8mb4;

ALTER TABLE `ai_slo_policy`
    ADD COLUMN `minimum_sample_count` INT NOT NULL DEFAULT 100
        COMMENT '短长窗口各自触发评估所需的最低调用样本数' AFTER `long_window_minutes`,
    ADD CONSTRAINT `chk_slo_policy_minimum_samples` CHECK (`minimum_sample_count` > 0);
