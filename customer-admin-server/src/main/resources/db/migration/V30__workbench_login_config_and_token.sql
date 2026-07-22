-- =============================================================================
-- 内网工作台 自动登录扩展（Flyway V30）
-- =============================================================================
-- 在 V29 的 workbench_site 基础上，增加 ScriptCat 通用脚本所需的自动登录配置字段，
-- 并新建个人访问令牌表 workbench_token（脚本回调 admin-server 取凭证时鉴权）。
--
-- 手工同步注意：走 stdin 管道 apply 时字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
-- =============================================================================

SET NAMES utf8mb4;

-- ----- workbench_site 增加自动登录配置（全部可空/带默认，留空走脚本内启发式）-----
ALTER TABLE `workbench_site`
    ADD COLUMN `username_selector` VARCHAR(255) NULL COMMENT '用户名输入框 CSS 选择器，留空用启发式' AFTER `enabled`,
    ADD COLUMN `password_selector` VARCHAR(255) NULL COMMENT '密码输入框 CSS 选择器，留空用 input[type=password]' AFTER `username_selector`,
    ADD COLUMN `submit_selector`   VARCHAR(255) NULL COMMENT '登录按钮 CSS 选择器，留空用启发式' AFTER `password_selector`,
    ADD COLUMN `fill_mode`         VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT '填充模式：auto=原生setter一次性 / typing=逐字模拟（顽固React如Kibana）' AFTER `submit_selector`,
    ADD COLUMN `submit_mode`       VARCHAR(16) NOT NULL DEFAULT 'click' COMMENT '提交方式：click=点按钮 / formSubmit=表单提交' AFTER `fill_mode`,
    ADD COLUMN `init_delay_ms`     INT NOT NULL DEFAULT 500 COMMENT '进页面后开始查找元素的延迟毫秒' AFTER `submit_mode`,
    ADD COLUMN `submit_delay_ms`   INT NOT NULL DEFAULT 300 COMMENT '填完到点击提交的延迟毫秒' AFTER `init_delay_ms`;

-- ----- 个人访问令牌表 -----
CREATE TABLE IF NOT EXISTS `workbench_token` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`        BIGINT NOT NULL COMMENT '令牌所属用户ID',
    `name`           VARCHAR(64) NOT NULL COMMENT '令牌用途备注',
    `token_hash`     CHAR(64) NOT NULL COMMENT '令牌明文的 SHA-256 十六进制，明文只在创建时返回一次',
    `token_prefix`   VARCHAR(16) NOT NULL COMMENT '令牌前缀（如 wbt_ab12cd34），列表展示用',
    `expire_time`    DATETIME NULL COMMENT '过期时间，NULL 表示永不过期',
    `last_used_time` DATETIME NULL COMMENT '最近一次使用时间',
    `revoked`        TINYINT NOT NULL DEFAULT 0 COMMENT '是否已吊销：0否 / 1是',
    `create_by`      BIGINT COMMENT '创建人ID',
    `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`      BIGINT COMMENT '更新人ID',
    `update_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_workbench_token_hash` (`token_hash`),
    KEY `idx_workbench_token_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内网工作台个人访问令牌';
