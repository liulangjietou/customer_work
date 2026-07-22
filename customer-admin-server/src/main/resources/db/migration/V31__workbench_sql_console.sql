-- =============================================================================
-- 我的工作台 - SQL 客户端（Flyway V31）
-- =============================================================================
-- 在"我的工作台"菜单下新增交互式 SQL 客户端：选已配置数据源 + 手写只读 SQL 直接查询。
-- 复用 sqlconfig 既有能力（数据源/连接池/只读校验/结果引擎/xlsx 导出），本迁移只加权限种子，
-- 不建新表（即席查询无持久化）。自由查库是高危能力，故用独立权限点，默认仅超管，其余按需授权。
--
-- 手工同步注意：走 stdin 管道 apply 时字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
-- =============================================================================

SET NAMES utf8mb4;

-- 二级菜单：SQL 客户端（挂"我的工作台" parent_id=160 下，与内网工作台 161 并列）。
-- 菜单可见性即 sql-console:query 执行权限（与现有 sql-query:view 合一模式一致）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (165, 160, 'SQL 客户端', 'sql-console:query', 1, '/workbench/sql-console', 'DataAnalysis', 'library', 2);

-- 三级：导出按钮权限点（type=2，挂 165 下）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (166, 165, '导出SQL查询结果', 'sql-console:export', 2, 1);
