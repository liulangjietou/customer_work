-- =============================================================================
-- 通用站内消息 + AI 代码审查异步任务（Flyway V28）
-- =============================================================================
-- 站内消息（ai_site_message）：面向后台 admin 用户的通用消息中心，任意业务域可通过
-- SiteMessageService.send 投递（biz_type 区分来源，如 CODE_REVIEW），前端消息中心统一拉取。
-- 代码审查任务（ai_code_review_task）：AI 代码审查从"同步等结果"改为"提交-轮询"，模型分钟级
-- 调用不再阻塞前端；提交即落 RUNNING 行返回 taskId，异步完成后回写结果并发站内信。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
-- =============================================================================

SET NAMES utf8mb4;

-- 通用站内消息：一条消息一个接收人（admin 用户），未读优先展示。
CREATE TABLE IF NOT EXISTS `ai_site_message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '接收人（admin 用户 id）',
    `title`       VARCHAR(200) NOT NULL COMMENT '消息标题',
    `content`     VARCHAR(2000)         DEFAULT NULL COMMENT '消息正文',
    `biz_type`    VARCHAR(50)  NOT NULL COMMENT '业务类型（如 CODE_REVIEW）',
    `biz_id`      VARCHAR(64)           DEFAULT NULL COMMENT '业务主键',
    `link`        VARCHAR(500)          DEFAULT NULL COMMENT '前端跳转路由（可空）',
    `read_flag`   TINYINT      NOT NULL DEFAULT 0 COMMENT '已读标记：0未读/1已读',
    `read_time`   DATETIME              DEFAULT NULL COMMENT '标记已读时间',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_read` (`user_id`, `read_flag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='通用站内消息';

-- AI 代码审查异步任务：提交即 RUNNING，异步完成回写 SUCCESS/FAILED 与结果。
CREATE TABLE IF NOT EXISTS `ai_code_review_task` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `agent_code`  VARCHAR(64) NOT NULL COMMENT '智能体编码',
    `session_id`  VARCHAR(64) NOT NULL COMMENT '会话 id',
    `user_id`     BIGINT      NOT NULL COMMENT '提交人（admin 用户 id）',
    `status`      VARCHAR(20) NOT NULL COMMENT '状态：RUNNING/SUCCESS/FAILED',
    `result_json` MEDIUMTEXT           DEFAULT NULL COMMENT '审查结果 JSON（SUCCESS 才有）',
    `error_msg`   VARCHAR(1000)        DEFAULT NULL COMMENT '失败原因（FAILED 才有）',
    `create_time` DATETIME    NOT NULL COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL COMMENT '更新时间',
    `finish_time` DATETIME             DEFAULT NULL COMMENT '完成时间（SUCCESS/FAILED 时写入）',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_session` (`session_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AI 代码审查异步任务';
