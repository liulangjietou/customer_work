-- 后台委派任务（agent_spawn 的异步模式）持久化 + 管理台菜单权限。
--
-- 框架的 agent_spawn 传 timeout_seconds=0 时，子智能体任务被提交给 TaskRepository 异步执行。
-- 框架自带实现 WorkspaceTaskRepository 把任务状态写在 agent 工作区的文件里——那份状态只服务
-- "父智能体在 ReAct 循环里回头查自己派出去的任务"，管理台既查不到（跨会话、跨智能体要扫全盘文件），
-- 也没法做权限与审计。HarnessAgent.Builder 暴露了 taskRepository(...) 注入点，故这里建表，由
-- MybatisTaskRepository 落库接管，让后台任务成为管理台的一等公民（列表/详情/取消）。
--
-- 表放 admin 库而不是客服端库：这是后台智能体（AdminAgentInstanceFactory 装配的那批）的运行产物，
-- 与客服端链路无关，没有跨库共享的需要。

CREATE TABLE IF NOT EXISTS `ai_agent_task` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`           VARCHAR(64)  NOT NULL COMMENT '框架任务ID（agent_spawn 返回给模型的那个，全局唯一）',
    `parent_agent_code` VARCHAR(64)           DEFAULT NULL COMMENT '发起任务的父智能体编码',
    `sub_agent_id`      VARCHAR(128)          DEFAULT NULL COMMENT '执行任务的子智能体标识',
    `parent_session_id` VARCHAR(128)          DEFAULT NULL COMMENT '父会话ID（任务归属的对话）',
    `status`            VARCHAR(16)  NOT NULL COMMENT '状态：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
    `result`            MEDIUMTEXT            DEFAULT NULL COMMENT '任务成功时的结果文本',
    `error_message`     TEXT                  DEFAULT NULL COMMENT '任务失败时的错误信息',
    `cancel_requested`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已请求取消（1=是）',
    `created_at`        DATETIME     NOT NULL COMMENT '任务创建时间',
    `started_at`        DATETIME              DEFAULT NULL COMMENT '开始执行时间（进入 RUNNING）',
    `finished_at`       DATETIME              DEFAULT NULL COMMENT '结束时间（进入任一终态）',
    `updated_at`        DATETIME     NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_session` (`parent_session_id`),
    KEY `idx_agent_status` (`parent_agent_code`, `status`),
    KEY `idx_created` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='智能体后台委派任务';

-- 菜单挂 AI 配置（parent_id=2）下、排在知识库之后：后台任务与"定时任务"（id=60）是同一类东西
-- ——智能体跑出来的任务记录，不该另起一级菜单。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (214, 2, '后台任务', 'agent-task:view', 1, '/aiconfig/agent-task', 'Timer', 'library', 9);

-- 取消是唯一的写操作：任务由智能体自己创建，管理台不提供手工新建。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (215, 214, '取消任务', 'agent-task:cancel', 2, 1);
