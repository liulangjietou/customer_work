-- =============================================================================
-- AI 编码操作审计日志（Flyway V13，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 落地《AI编码助手需求文档》§5.2/§5.3：所有 AI 编码操作记录审计日志（操作人、时间、
-- 变更文件、模型调用 token 数）。与 sys_operation_log（系统通用操作账）职责不同：
-- 本表是 AI 编码域专项审计，独有变更文件清单 / token 用量 / 会话维度三类可查询字段。
-- 菜单挂"系统管理"（id=1）下，只读页面仅建 view 权限点（参照操作日志 log:view 先例）；
-- 超级管理员不需要 sys_role_permission 记录（AdminStpInterfaceImpl 对超管返回全部权限）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_coding_audit_log` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`       BIGINT COMMENT '操作人用户ID',
    `username`      VARCHAR(64) COMMENT '操作人账号（冗余存储，用户改名/删除后历史仍可读）',
    `agent_code`    VARCHAR(64) NOT NULL COMMENT '智能体编码',
    `session_id`    VARCHAR(128) COMMENT '会话ID',
    `operation`     VARCHAR(32) NOT NULL COMMENT '操作类型：CHAT_STREAM/FILE_SAVE/GIT_DIFF_SUMMARY/COMMIT_MESSAGE/PR_DESCRIPTION',
    `changed_files` TEXT COMMENT '本次操作产生变更的文件清单（JSON数组），只读操作/无变更为空',
    `input_tokens`  INT COMMENT '模型输入token数（未发生模型调用或框架未返回用量时为空）',
    `output_tokens` INT COMMENT '模型输出token数',
    `total_tokens`  INT COMMENT '模型总token数',
    `duration_ms`   BIGINT COMMENT '操作耗时（毫秒）',
    `result`        TINYINT NOT NULL COMMENT '结果：1成功 / 0失败',
    `error_code`    VARCHAR(64) COMMENT '失败错误码（ResultCode枚举名或流终止信号），成功为空',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX `idx_ai_coding_audit_agent_session` (`agent_code`, `session_id`),
    INDEX `idx_ai_coding_audit_user` (`user_id`),
    INDEX `idx_ai_coding_audit_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI编码操作审计日志';

-- 菜单：AI编码审计（挂系统管理下。id 跳跃分配到 100：V12 的按钮权限自增已把 id 用到 70，
-- 60~70 全被占用，跳到 100 给后续迁移留出安全空间）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (100, 1, 'AI编码审计', 'ai-audit', 1, '/system/ai-audit', 'DataAnalysis', 'library', 5);

-- 按钮/接口权限点：只读页面仅 view（参照操作日志 log:view 先例，不建 add/edit/delete）
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (100, '查看AI编码审计', 'ai-audit:view', 2, 1);
