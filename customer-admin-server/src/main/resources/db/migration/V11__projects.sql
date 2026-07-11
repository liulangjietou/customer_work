-- =============================================================================
-- Projects：跨智能体的会话分组（Flyway V11，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 会话本身没有落自建表（详见 ChatHistoryService 类注释：直接读 AgentStateStore 框架表，
-- sessionId 是"某个 agentCode 分区下的逻辑 key"，没有自增主键），所以关联表不能像
-- ai_agent_mcp/ai_agent_skill 那样用两个数字外键，只能用 (agent_code, session_id) 复合列关联。
--
-- 权限点复用一级菜单"智能体工作区"已经在用的 workspace（同一层级、同一使用场景：能进工作区聊天
-- 就能把自己的会话归类进项目），不新增 project:* 按钮权限——项目管理本身也不是需要单独授权收紧
-- 的敏感操作，跟系统管理/AI 配置那类需要精细化 RBAC 的模块性质不同。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_project` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `project_name`  VARCHAR(64) NOT NULL COMMENT '项目名称',
    `description`   VARCHAR(255) COMMENT '描述',
    `create_by`     BIGINT COMMENT '创建人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT COMMENT '更新人ID',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话项目分组（跨智能体自由归类会话）';

CREATE TABLE IF NOT EXISTS `ai_project_session` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `project_id`    BIGINT NOT NULL COMMENT '项目ID',
    `agent_code`    VARCHAR(64) NOT NULL COMMENT '会话所属智能体编码',
    `session_id`    VARCHAR(255) NOT NULL COMMENT '会话ID（AgentStateStore 里的逻辑 key，非本表自增主键）',
    `create_by`     BIGINT COMMENT '添加人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
    UNIQUE KEY `uk_ai_project_session` (`project_id`, `agent_code`, `session_id`),
    INDEX `idx_ai_project_session_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目-会话关联';

-- 一级菜单：Projects（跟"智能体工作区"同级、无子节点，MenuTree.vue 按 children 是否为空自动渲染成
-- 直接可点的叶子菜单，不用额外处理）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (4, 0, 'Projects', 'project', 1, '/project', 'Files', 'library', 4);
