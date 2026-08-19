-- Skill 技能包下载的权限点。
--
-- 单独发权限点，没有复用 skill:view——判断标准与 V44（内容风控"导出复用 view"）一致，只是结论相反：
-- 那边页面上本来就能看到全部词条内容，导出只是同一份数据换个格式；而这里 skill:view 返回的附属文件
-- 清单只有路径与大小（SkillFileVO 不带内容），文件字节此前在后台任何接口都拿不到。
-- 附属文件里可能有脚本与内网文档，"能看到有哪些文件"和"能把整包带走"是两种能力。
--
-- 命名取 skill:export 而非 skill:download，与既有的 billing:export / sql-console:export / sql-query:export 一致。
--
-- id 247：本机 sys_permission 已用到 246，247 起是既定约定。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接返回全部权限点
-- （沿用 V35/V36/V38/V41 同款做法，普通角色需在角色管理页手工授权）。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。

SET NAMES utf8mb4;

-- parent_id=22 是"Skill 管理"菜单（同级已有 41 view / 42 add / 43 edit / 44 delete，本项 sort=5 排在最后）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (247, 22, '下载技能包', 'skill:export', 2, 5);
