-- =============================================================================
-- 系统工具（Flyway V15，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 维护一个"系统工具"目录：工具实现是代码定义的（一个 @Tool 注解的 Spring Bean，bean name 精确
-- 等于 tool_code），库里只存"该工具是否启用 + 展示用名称/描述/备注"，不存工具逻辑本身。新增一个
-- 工具 = 写一个新 @Tool 类 + 本迁移加一行种子；因此系统工具管理页没有新增/删除，只有启停 + 编辑。
-- 智能体配置页可像挂 MCP/Skill 一样多选系统工具（ai_agent_system_tool 关联表），聊天运行时把勾选且
-- enabled=1 的工具按 tool_code 从 Spring 容器取 Bean 注册进该智能体的 Toolkit。
--
-- 本轮只有一条种子：httpclient（HTTP 请求工具，提供 GET/DELETE/POST/POST-Form/PUT 五种调用）。
-- 安全说明：该 HTTP 工具的 TLS 走 JDK 默认信任链校验（不做 trust-all / 不关主机名校验）；但它允许
-- 挂载该工具的智能体请求任意可达 URL（含内网地址），是一个 SSRF 风险面，是否收紧到 URL 白名单本轮
-- 先不做，后续按需迭代。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：菜单 id=120（V13 用到 100、V14 用到 110-112，跳到 120 留安全间隔），parent_id=2
-- 挂在"AI 配置"目录下，sort=6。按钮/接口权限点只有 view/edit（无 add/delete）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接放行
-- 全部权限点（沿用 V12/V13/V14 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_system_tool` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tool_code`   VARCHAR(64) NOT NULL COMMENT '工具编码（唯一，运行时按此值取同名 Spring Bean，不可修改）',
    `tool_name`   VARCHAR(128) NOT NULL COMMENT '工具展示名称',
    `description` VARCHAR(512) COMMENT '工具描述',
    `enabled`     TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0禁用 / 1启用',
    `remark`      VARCHAR(255) COMMENT '备注',
    `create_by`   BIGINT COMMENT '创建人ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT COMMENT '更新人ID',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_ai_system_tool_code` (`tool_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统工具目录（代码定义，库存启停状态）';

CREATE TABLE IF NOT EXISTS `ai_agent_system_tool` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `agent_id`       BIGINT NOT NULL COMMENT '智能体ID',
    `system_tool_id` BIGINT NOT NULL COMMENT '系统工具ID',
    INDEX `idx_ai_agent_system_tool_agent` (`agent_id`),
    INDEX `idx_ai_agent_system_tool_tool` (`system_tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-系统工具关联（纯关系表）';

-- 种子：HTTP 请求工具（tool_code 精确等于 @Component("httpclient") 的 Bean 名）。
INSERT INTO `ai_system_tool` (`id`, `tool_code`, `tool_name`, `description`, `enabled`, `remark`)
VALUES (1, 'httpclient', 'HTTP请求工具', 'http请求工具', 1, 'http请求工具');

-- 菜单：系统工具（挂 AI 配置 id=2 下，sort=6）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (120, 2, '系统工具', 'system-tool', 1, '/aiconfig/system-tool', 'SetUp', 'library', 6);

-- 按钮/接口权限点（type=2）：只有 view/edit（系统工具不支持新增/删除）。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (120, '查看系统工具', 'system-tool:view', 2, 1),
    (120, '编辑系统工具', 'system-tool:edit', 2, 2);
