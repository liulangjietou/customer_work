-- =============================================================================
-- 定时任务 cron 字段（Flyway V21）
-- =============================================================================
-- 背景：ai_scheduled_task 原先不落调度周期，周期调度强依赖外部 XXL-JOB 控制台。现在
-- admin-server 内置 Spring 动态调度器（ScheduledTaskScheduler），页面直接配 cron，无 XXL-JOB
-- 也能按周期跑；配了 XXL-JOB 时可切到外部调度模式（admin.scheduler.mode=xxl-job）。
--
-- cron 可空，兼容存量数据：存量任务 cron 为 NULL 时不参与内置周期调度（只能手动触发 / 外部 XXL-JOB
-- 触发），语义与升级前完全一致，无需数据回填。
-- 内置调度与 XXL-JOB 互斥执行由 admin.scheduler.mode 全局门控，避免双跑（见 ScheduledTaskJobHandler）。
-- =============================================================================

ALTER TABLE `ai_scheduled_task`
    ADD COLUMN `cron` VARCHAR(64) NULL COMMENT 'cron 表达式（内置动态调度器按此周期执行；为空则不参与内置周期调度，仅可手动/外部触发）' AFTER `prompt`;
