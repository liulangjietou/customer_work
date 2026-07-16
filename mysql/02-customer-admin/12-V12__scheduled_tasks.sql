-- =============================================================================
-- 定时任务（Flyway V12，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 基于 AgentScope 的定时任务：ai_scheduled_task 是"任务定义"（哪个智能体、什么 prompt、
-- 启停开关），ai_scheduled_task_run 是"每次执行的历史流水"（手动触发 / XXL-JOB 触发都落一条）。
-- 调度周期本身不落本表——周期/启停/重试统一在 XXL-JOB 控制台管理（对齐
-- customer-work-spring-boot-starter 的 XxlJobSchedulerConfig 设计），本表 enabled 只控制
-- "该任务是否允许被执行"（disabled 时无论 XXL-JOB 触发还是手动触发都 fast-fail 拒绝）。
--
-- ai_scheduled_task_run 是只追加的执行日志，不做逻辑删除/审计人字段（跟 sys_menu_change_log
-- 同一类"排查用日志表"的从简处理，不是需要变更追溯的业务实体）；output 落库前截断到 8000
-- 字符（ScheduledTaskService 侧），避免模型输出过长把这张日志表拖成大字段黑洞。
--
-- 权限点挂 AI 配置菜单树（parent_id=2，同 model/mcp/skill/agent 一层），按钮沿用
-- view/add/edit/delete 四件套 + 定时任务特有的 trigger（手动触发，有副作用需要单独授权收紧，
-- 不能像 mcp 连通性测试那样复用 view）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_scheduled_task` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_code`     VARCHAR(64) NOT NULL COMMENT '任务编码（= XXL-JOB JobHandler 调用时的业务标识，全局唯一）',
    `task_name`     VARCHAR(64) NOT NULL COMMENT '任务名称',
    `agent_id`      BIGINT NOT NULL COMMENT '关联智能体ID（逻辑关联 ai_agent.id）',
    `prompt`        TEXT NOT NULL COMMENT '每次触发时传给 Agent 的用户消息内容',
    `enabled`       TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许执行：0禁用 / 1启用',
    `remark`        VARCHAR(255) COMMENT '备注',
    `create_by`     BIGINT COMMENT '创建人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT COMMENT '更新人ID',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_ai_scheduled_task_code` (`task_code`),
    INDEX `idx_ai_scheduled_task_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务定义';

CREATE TABLE IF NOT EXISTS `ai_scheduled_task_run` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`       BIGINT NOT NULL COMMENT '任务ID',
    `task_code`     VARCHAR(64) NOT NULL COMMENT '任务编码（冗余，任务被删除后历史记录仍可读）',
    `trigger_type`  VARCHAR(16) NOT NULL COMMENT '触发方式：XXL_JOB / MANUAL',
    `start_time`    DATETIME NOT NULL COMMENT '开始执行时间',
    `end_time`      DATETIME COMMENT '结束时间',
    `cost_ms`       BIGINT COMMENT '耗时（毫秒）',
    `status`        VARCHAR(16) NOT NULL COMMENT '执行结果：SUCCESS / FAILED',
    `output`        TEXT COMMENT 'Agent 回复内容（落库前截断到 8000 字符）',
    `error_message` VARCHAR(1000) COMMENT '失败原因',
    INDEX `idx_ai_scheduled_task_run_task_time` (`task_id`, `start_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务执行历史（只追加，不做逻辑删除）';

-- 二级菜单：定时任务，挂在 AI 配置（id=2）下，与 model(20)/mcp(21)/skill(22)/agent(23) 同层。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (60, 2, '定时任务', 'scheduler', 1, '/aiconfig/scheduled-task', 'Timer', 'library', 5);

-- 三级：按钮/接口权限点（type=2）。trigger 有副作用（真实触发一次 Agent 执行），单独授权，不复用 view。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (60, '查看定时任务', 'scheduler:view',    2, 1),
    (60, '新增定时任务', 'scheduler:add',     2, 2),
    (60, '编辑定时任务', 'scheduler:edit',    2, 3),
    (60, '删除定时任务', 'scheduler:delete',  2, 4),
    (60, '手动触发定时任务', 'scheduler:trigger', 2, 5);
