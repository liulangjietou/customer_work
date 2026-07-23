-- =============================================================================
-- 登录页轮播图管理（Flyway V35）
-- =============================================================================
-- 后台可上传多张登录页轮播背景图（原图落盘 ./data/login-images，表里存相对访问 URL
-- /api/login-images/{uuid}.{ext}），支持排序/启停/删除；登录页未登录状态通过免鉴权接口
-- GET /api/login-images/list 实时拉取启用图列表，拉不到或列表为空时前端回退内置默认图。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：id 从 180 起——V31 显式 id 用到 166，但 V27 有一条不带显式 id 的插入吃掉了
-- 自增号段（本机实测已分配到 170），170 段不安全，跳到 180 留足空间。超级管理员（super_admin）
-- 无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接返回全部权限点
-- （沿用 V29 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `login_carousel_image` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `image_name`  VARCHAR(255) COMMENT '上传时的原始文件名（管理页展示用）',
    `image_url`   VARCHAR(255) NOT NULL COMMENT '对外访问相对URL：/api/login-images/{uuid}.{ext}',
    `sort_order`  INT NOT NULL DEFAULT 0 COMMENT '轮播顺序，小的在前',
    `enabled`     TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0禁用 / 1启用',
    `create_by`   BIGINT COMMENT '创建人ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT COMMENT '更新人ID',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    KEY `idx_login_carousel_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录页轮播背景图';

-- 二级菜单：登录页图片（挂"系统管理" id=1 下，sort=7 排在开发者工具箱之后）。菜单可见性即 view 权限点。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (180, 1, '登录页图片', 'login-image:view', 1, '/system/login-image', 'Picture', 'library', 7);

-- 三级：按钮/接口权限点（type=2，挂 180 下）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (181, 180, '上传登录页图片', 'login-image:add',    2, 1),
    (182, 180, '编辑登录页图片', 'login-image:edit',   2, 2),
    (183, 180, '删除登录页图片', 'login-image:delete', 2, 3);
