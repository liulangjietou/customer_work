-- =============================================================================
-- 内网工作台（Flyway V29）
-- =============================================================================
-- 团队共享的内网系统账号本（git/jenkins/oa 等）：维护 名称/分类/地址/账号/密码/备注，
-- 前端列表页支持 CRUD + 一键复制账号密码 + 新标签页打开地址。密码 AES-GCM 密文入库，
-- 永不明文返回（列表回显掩码，复制密码走单独的 /secret 敏感读接口并审计）。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：id 跳跃分配到 160 起（V27 开发者工具箱已把显式 id 用到 150，中间留出安全空间）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接
-- 返回全部权限点（沿用 V14/V19/V20 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `workbench_site` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`        VARCHAR(64) NOT NULL COMMENT '系统名称',
    `category`    VARCHAR(32) COMMENT '分类：如 git/jenkins/oa',
    `url`         VARCHAR(512) NOT NULL COMMENT '访问地址',
    `account`     VARCHAR(128) COMMENT '登录账号',
    `password`    VARCHAR(512) COMMENT '登录密码（AES-GCM 密文，永不明文返回）',
    `remark`      VARCHAR(255) COMMENT '备注',
    `enabled`     TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0禁用 / 1启用',
    `create_by`   BIGINT COMMENT '创建人ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT COMMENT '更新人ID',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_workbench_site_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内网工作台系统账号本';

-- 一级目录：我的工作台（顶层菜单 parent_id=0，无路由 path，仅作分组）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (160, 0, '我的工作台', 'workbench', 1, '', 'Grid', 'library', 7);

-- 二级菜单：内网工作台（type=1，挂 160 下）。菜单可见性即 view 权限点。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (161, 160, '内网工作台', 'workbench-site:view', 1, '/workbench/site', 'Monitor', 'library', 1);

-- 三级：按钮/接口权限点（type=2，挂 161 下）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (162, 161, '新增内网工作台站点', 'workbench-site:add',    2, 1),
    (163, 161, '编辑内网工作台站点', 'workbench-site:edit',   2, 2),
    (164, 161, '删除内网工作台站点', 'workbench-site:delete', 2, 3);
