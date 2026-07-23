-- =============================================================================
-- 渠道接入管理（Flyway V36）
-- =============================================================================
-- 让钉钉机器人（后续预留企微/微信）能与后台"智能体工作区"的智能体对话：后台维护渠道机器人
-- （app_key/app_secret + 绑定 agent_code），并通过开放 API（/api/open/**，X-Open-Api-Token 鉴权）
-- 供 customer-channel 模块拉取机器人配置、解析外部用户会话、发起流式对话。
--
-- ai_channel_robot   : 渠道机器人，app_secret 以 AES-GCM 密文存储（app_secret_cipher），永不明文回列表。
-- ai_channel_session : 渠道外部用户(external_user_id) ↔ 工作区会话(session_id) 的稳定映射，
--                      同一外部用户多轮对话复用同一 session_id，reset 时生成新 session_id 覆盖。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：id 从 190 起（V35 显式 id 用到 183，190 起是既定约定，留足安全空间）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接
-- 返回全部权限点（沿用 V29/V35 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_channel_robot` (
    `id`                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    `channel_type`       VARCHAR(32) NOT NULL COMMENT '渠道类型：dingtalk（预留 wecom/wechat）',
    `robot_name`         VARCHAR(64) NOT NULL COMMENT '机器人名称',
    `app_key`            VARCHAR(128) NOT NULL COMMENT '渠道 AppKey / ClientId',
    `app_secret_cipher`  VARCHAR(512) NOT NULL COMMENT 'AppSecret（AES-GCM 密文，永不明文返回列表）',
    `robot_code`         VARCHAR(128) COMMENT '机器人编码（钉钉 robotCode 等，选填）',
    `agent_code`         VARCHAR(64) NOT NULL COMMENT '绑定的智能体编码（ai_agent.agent_code）',
    `status`             TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用 / 1启用',
    `remark`             VARCHAR(255) COMMENT '备注',
    `create_time`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_channel_appkey` (`channel_type`, `app_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道机器人';

CREATE TABLE IF NOT EXISTS `ai_channel_session` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `channel_type`      VARCHAR(32) NOT NULL COMMENT '渠道类型：dingtalk（预留 wecom/wechat）',
    `app_key`           VARCHAR(128) NOT NULL COMMENT '渠道 AppKey / ClientId',
    `external_user_id`  VARCHAR(191) NOT NULL COMMENT '渠道侧外部用户唯一标识（钉钉 senderStaffId 等）',
    `session_id`        VARCHAR(64) NOT NULL COMMENT '映射到的工作区会话 ID（ch-<uuid>）',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_channel_user` (`channel_type`, `app_key`, `external_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道外部用户与工作区会话映射';

-- 二级菜单：渠道接入（挂"AI 配置" id=2 下，sort=7 排在系统工具 sort=6 之后）。菜单可见性即 view 权限点。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (190, 2, '渠道接入', 'channel-robot:view', 1, '/aiconfig/channel-robot', 'Connection', 'library', 7);

-- 三级：按钮/接口权限点（type=2，挂 190 下）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (191, 190, '新增渠道机器人', 'channel-robot:add',    2, 1),
    (192, 190, '编辑渠道机器人', 'channel-robot:edit',   2, 2),
    (193, 190, '删除渠道机器人', 'channel-robot:delete', 2, 3);
