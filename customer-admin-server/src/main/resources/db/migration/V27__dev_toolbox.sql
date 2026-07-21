-- =============================================================================
-- 开发者工具箱（Flyway V27，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 新增一个系统工具 devtoolbox（开发者工具箱），沿用 V15 定的"系统工具目录"范式：工具实现是代码
-- 定义的（@Tool 注解的 Spring Bean，bean name 精确等于 tool_code），库里只存启停 + 展示名/描述。
-- 该工具聚合一组纯本地计算能力：JSON 格式化/压缩/校验、时间戳转换、Base64/URL 编解码、
-- 哈希(HMAC)、UUID 生成、AES 加解密、正则测试；均为本地计算，不访问外部资源，无 SSRF 面。
--
-- 前端页面是纯本地计算（不依赖后端接口），因此本菜单只需一个"查看"可见性权限点，不设 add/edit/delete。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：菜单 id=150（当前显式 id 最大为 141，跳到 150 留安全间隔），parent_id=1 挂在
-- "系统管理"目录下，sort=6（现有子菜单 user/role/log/menu/ai-audit 最大 sort=5，顺延取 6）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接放行
-- 全部权限点（沿用 V15 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

-- 种子：开发者工具箱（tool_code 精确等于 @Bean(name="devtoolbox") 的 Bean 名）。id 取当前最大 id(1)+1=2。
INSERT INTO `ai_system_tool` (`id`, `tool_code`, `tool_name`, `description`, `enabled`, `remark`)
VALUES (2, 'devtoolbox', '开发者工具箱',
        '开发者常用本地工具集：JSON格式化/压缩/校验、时间戳转换、Base64/URL编解码、哈希(HMAC)、UUID生成、AES加解密、正则测试',
        1, '纯本地计算，无外部依赖');

-- 菜单：开发者工具箱（挂系统管理 id=1 下，sort=6）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (150, 1, '开发者工具箱', 'devtools', 1, '/system/devtools', 'Tools', 'library', 6);

-- 按钮/接口权限点（type=2）：仅一条 view（前端纯本地计算，只需菜单可见性权限）。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (150, '查看开发者工具箱', 'devtools:view', 2, 1);
