SET NAMES utf8mb4;

-- 内置 cron 多 Pod 全局认领：同一任务同一计划触发时刻只允许一个实例插入成功。
CREATE TABLE IF NOT EXISTS `ai_scheduled_task_claim` (
    `tenant_id`  VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '任务所属租户',
    `task_id`    BIGINT NOT NULL COMMENT '定时任务ID',
    `task_code`  VARCHAR(64) NOT NULL COMMENT '任务编码快照',
    `fire_time`  DATETIME(3) NOT NULL COMMENT 'Cron 计算出的计划触发时刻',
    `owner_id`   VARCHAR(64) NOT NULL COMMENT '成功认领的 Admin 实例ID',
    `claim_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '认领时间',
    PRIMARY KEY (`tenant_id`, `task_id`, `fire_time`),
    INDEX `idx_scheduled_task_claim_time` (`claim_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内置定时任务多Pod触发认领';
