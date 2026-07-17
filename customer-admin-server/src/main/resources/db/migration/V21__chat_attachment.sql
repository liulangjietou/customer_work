-- =============================================================================
-- 对话附件多格式解析落库（Flyway V21，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 落地"附件多格式解析"需求：智能体对话（ChatPanel）/VibeCoding（VibeCodingPanel）上传的
-- 附件（图片走视觉大模型 OCR，pdf/office/html 走 Tika/POI，md/txt/csv/json 直读）统一落盘 + 落库，
-- 保证解析结果可追溯（含解析失败的 FAILED 记录）。字段与 starter 的 cw_chat_attachment 对齐，
-- 另加 agent_code：admin 侧一次上传天然归属某个智能体（路径参数 {agentCode}），便于按智能体维度追溯。
--
-- 表名走 admin 的 ai_ 前缀约定（与 ai_chat_session_state / ai_coding_audit_log 一致）；解析文本用
-- MEDIUMTEXT（单文档解析可能上万字，超长由应用层 max-parsed-chars 截断）。本表纯业务数据表，不登记菜单/权限点。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接放行。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_chat_attachment` (
    `id`            VARCHAR(64)  NOT NULL COMMENT '附件ID(UUID)',
    `session_id`    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '会话ID',
    `agent_code`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '智能体编码（一次上传归属的智能体）',
    `uploader`      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '上传者标识（当前登录管理员ID）',
    `channel`       VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '来源渠道：admin_chat/vibecoding',
    `file_name`     VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `extension`     VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '扩展名',
    `mime_type`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'MIME类型',
    `file_size`     BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `storage_path`  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '落盘相对路径',
    `parse_status`  VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态：SUCCESS/FAILED',
    `parsed_text`   MEDIUMTEXT   NULL COMMENT '解析出的文本（FAILED 为空）',
    `error_message` VARCHAR(512) NULL COMMENT '解析失败原因（SUCCESS 为空）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_ai_attachment_session` (`session_id`),
    INDEX `idx_ai_attachment_agent` (`agent_code`),
    INDEX `idx_ai_attachment_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话附件（多格式解析落库）';
