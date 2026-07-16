-- =============================================================================
-- 菜单管理功能（Flyway V9，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 1. icon 列放宽存图片 URL（原来只存图标库组件名，≤64 字符够用；上传图片的本地路径可能更长）；
--    新增 icon_type 区分"图标库图标名"与"上传图片 URL"，避免前端靠猜字符串格式判断（魔法值）。
-- 2. 新增菜单变更审计流水表：记录每次增/删/改/拖拽移动的操作人、时间、变更前后节点快照（JSON），
--    只用于排查"谁什么时候改了什么"，不做整树快照、不支持一键回滚。
-- 3. 新增"菜单管理"菜单项与按钮权限点，挂在"系统管理"（id=1）下，按钮权限沿用项目既有的
--    view/add/edit/delete 四件套约定（拖拽排序/发布广播/图标上传等衍生操作复用 menu:edit，
--    参考 mcp 模块"测试连接"复用 mcp:view 的先例，不为每个衍生动作单开权限码）。
-- =============================================================================

ALTER TABLE `sys_permission`
    MODIFY COLUMN `icon` VARCHAR(255) COMMENT '图标库图标名或上传图片URL（按 icon_type 区分）',
    ADD COLUMN `icon_type` VARCHAR(16) NOT NULL DEFAULT 'library' COMMENT '图标类型：library=图标库图标名，image=上传图片URL' AFTER `icon`;

CREATE TABLE IF NOT EXISTS `sys_menu_change_log` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `menu_id`         BIGINT NOT NULL COMMENT '被操作的菜单节点ID（删除后节点已不存在，此处仍保留历史指向）',
    `action`          VARCHAR(16) NOT NULL COMMENT '操作类型：CREATE/UPDATE/DELETE/MOVE',
    `before_snapshot` TEXT COMMENT '变更前节点 JSON（CREATE 时为空）',
    `after_snapshot`  TEXT COMMENT '变更后节点 JSON（DELETE 时为空）',
    `operator_id`     BIGINT COMMENT '操作人用户ID',
    `operator_name`   VARCHAR(64) COMMENT '操作人昵称（冗余存储，用户改名/删除后历史记录仍可读）',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_menu_change_log_menu` (`menu_id`),
    INDEX `idx_menu_change_log_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单变更审计流水（排查用，不支持回滚）';

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (13, 1, '菜单管理', 'menu', 1, '/system/menu', 'Menu', 'library', 4);

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (13, '查看菜单', 'menu:view',   2, 1),
    (13, '新增菜单', 'menu:add',    2, 2),
    (13, '编辑菜单', 'menu:edit',   2, 3),
    (13, '删除菜单', 'menu:delete', 2, 4);
