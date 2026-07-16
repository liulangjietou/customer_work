-- =============================================================================
-- 菜单图标个性化（Flyway V5，仅本地/测试 profile 自动执行）
-- =============================================================================
-- V2 的种子数据已经跑过、不能再改（Flyway 靠校验和识别已应用的迁移，改历史文件会导致后续启动
-- 校验失败），改用新迁移对已存在的行做 UPDATE 回填 icon 列。icon 存 Element Plus 图标组件名
-- （全局已注册，见 customer-admin-web/src/main.ts），具体颜色由前端 MenuTree.vue 按 perm_code
-- 映射着色，不在这里存颜色值（图标库换皮不用改数据）。
-- =============================================================================

UPDATE `sys_permission` SET `icon` = 'User'    WHERE `perm_code` = 'user';
UPDATE `sys_permission` SET `icon` = 'Avatar'  WHERE `perm_code` = 'role';
UPDATE `sys_permission` SET `icon` = 'Tickets' WHERE `perm_code` = 'log';

UPDATE `sys_permission` SET `icon` = 'Cpu'        WHERE `perm_code` = 'model';
UPDATE `sys_permission` SET `icon` = 'Connection' WHERE `perm_code` = 'mcp';
UPDATE `sys_permission` SET `icon` = 'Reading'    WHERE `perm_code` = 'skill';
UPDATE `sys_permission` SET `icon` = 'MagicStick' WHERE `perm_code` = 'agent';
