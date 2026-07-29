-- =============================================================================
-- 智能体调用分段耗时统计（Flyway V38）
-- =============================================================================
-- 在后台库 customer_admin 落地"智能体调用分段耗时"统计的两张表：调用主记录 + 分段明细。
-- 采集由 starter 的 AgentCallTimingMiddleware 完成，落库走 starter 的 MybatisAgentCallLogStore；
-- 后台把中间件/存储显式装配进 workspace 智能体链路（AdminAgentInstanceFactory + ChatService），
-- 并新增查询接口 /api/agent-call-stats/**（ADMIN 本库 + APP 客服端库 agent_scope_customer_work 双源）。
--
-- 【表名刻意带 cw_ 前缀，与 admin 的 ai_ 惯例不同】：DDL 从 starter 的 customer-work-schema.sql
-- 原样拷贝、列完全一致，换取直接复用 starter jar 内的 AgentCallLogMapper.xml / AgentCallSegmentMapper.xml
-- （namespace 绑定 starter 的 Mapper 接口，零改动）。若改名为 ai_agent_call_* 则 starter 的 XML 里
-- 硬编码的表名对不上，必须另写一套 Mapper/XML，得不偿失。故此处保持 cw_ 前缀。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：id 从 194 起（V36 显式 id 用到 193，194 起是既定约定，留足安全空间）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接
-- 返回全部权限点（沿用 V35/V36 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

-- 智能体调用主记录表（MybatisAgentCallLogStore）：一次调用一行，四类分段耗时冗余存储。
CREATE TABLE IF NOT EXISTS `cw_agent_call_log` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `request_id`     VARCHAR(64) NOT NULL DEFAULT '' COMMENT '请求ID（全链路关联）',
    `user_id`        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '用户ID（ctx.userId）',
    `username`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '用户名',
    `agent_code`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '智能体编码',
    `agent_name`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '智能体名称',
    `session_id`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '会话ID',
    `session_type`   VARCHAR(32) NOT NULL DEFAULT 'CHAT' COMMENT '会话类型 CHAT/VIBE_CODING',
    `question`       MEDIUMTEXT COMMENT '用户问题',
    `answer`         MEDIUMTEXT COMMENT '智能体回答',
    `start_time`     BIGINT NOT NULL COMMENT '调用开始时间戳（毫秒）',
    `end_time`       BIGINT NOT NULL COMMENT '调用结束时间戳（毫秒）',
    `duration_ms`    BIGINT NOT NULL DEFAULT 0 COMMENT '总耗时（毫秒）',
    `model_ms`       BIGINT NOT NULL DEFAULT 0 COMMENT 'MODEL段耗时合计（毫秒）',
    `tool_ms`        BIGINT NOT NULL DEFAULT 0 COMMENT 'TOOL段耗时合计（毫秒）',
    `mcp_ms`         BIGINT NOT NULL DEFAULT 0 COMMENT 'MCP段耗时合计（毫秒）',
    `skill_ms`       BIGINT NOT NULL DEFAULT 0 COMMENT 'SKILL段耗时合计（毫秒）',
    `segment_count`  INT NOT NULL DEFAULT 0 COMMENT '分段总数',
    `success`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '整次调用是否成功',
    `error_msg`      VARCHAR(1024) COMMENT '失败原因',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    INDEX `idx_call_request` (`request_id`),
    INDEX `idx_call_username` (`username`),
    INDEX `idx_call_agent_code` (`agent_code`),
    INDEX `idx_call_session` (`session_id`),
    INDEX `idx_call_start` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体调用主记录（分段耗时统计）';

-- 智能体调用分段明细表（MybatisAgentCallLogStore）：一次调用的每段耗时一行。
CREATE TABLE IF NOT EXISTS `cw_agent_call_segment` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `call_log_id`    BIGINT NOT NULL COMMENT '所属主记录ID',
    `seq`            INT NOT NULL COMMENT '调用内分段序号（从1起）',
    `kind`           VARCHAR(16) NOT NULL COMMENT '分段类别 MODEL/TOOL/MCP/SKILL',
    `name`           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '分段名称（模型名/工具名）',
    `start_time`     BIGINT NOT NULL COMMENT '分段开始时间戳（毫秒）',
    `duration_ms`    BIGINT NOT NULL DEFAULT 0 COMMENT '分段耗时（毫秒）',
    `success`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '分段是否成功',
    `error_msg`      VARCHAR(1024) COMMENT '失败原因',
    INDEX `idx_segment_call_log` (`call_log_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体调用分段明细';

-- 二级菜单：智能体耗时统计（挂"系统管理" id=1 下，参照 AI 编码审计 id=100 的父子结构）。
-- 菜单可见性即 view 权限点（perm_code=agent-call-stats:view）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (194, 1, '智能体耗时统计', 'agent-call-stats:view', 1, '/system/agent-call-stats', 'Timer', 'library', 6);

-- 三级：按钮/接口权限点（type=2，挂 194 下）。只读报表仅一个删除按钮权限。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (195, 194, '删除调用日志', 'agent-call-stats:delete', 2, 1);
