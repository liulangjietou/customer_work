SET NAMES utf8mb4;

-- Plan/HITL 挂起态权威表：本地 SSE 通道可随 Pod 生命周期结束，但决策状态必须跨 Pod 可见。
CREATE TABLE IF NOT EXISTS `ai_plan_confirmation` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id`   VARCHAR(64) NOT NULL COMMENT '租户ID',
    `agent_code`  VARCHAR(191) NOT NULL COMMENT '智能体编码',
    `session_id`  VARCHAR(191) NOT NULL COMMENT '会话ID',
    `plan_id`     VARCHAR(64) NOT NULL COMMENT '计划ID',
    `status`      VARCHAR(16) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/TIMEOUT/CANCELLED',
    `expire_at`   DATETIME NOT NULL COMMENT '确认截止时间',
    `resolved_at` DATETIME NULL COMMENT '终态时间',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_confirmation_scope` (`tenant_id`, `agent_code`, `session_id`, `plan_id`),
    KEY `idx_plan_confirmation_pending` (`tenant_id`, `status`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Plan/HITL 跨 Pod 挂起态';

-- 长期记忆使用显式乐观锁版本，避免两个 Pod 基于旧工作副本相互覆盖。
SET @v86_memory_table_exists = (
    SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'ai_agent_memory' AND table_type = 'BASE TABLE'
);
SET @v86_memory_preflight = IF(
    @v86_memory_table_exists = 1,
    'SELECT 1',
    'SELECT * FROM `__customer_admin_v86_ai_agent_memory_required__`'
);
PREPARE v86_memory_preflight_stmt FROM @v86_memory_preflight;
EXECUTE v86_memory_preflight_stmt;
DEALLOCATE PREPARE v86_memory_preflight_stmt;

SET @v86_memory_version_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ai_agent_memory' AND column_name = 'version'
);
SET @v86_memory_version_ddl = IF(
    @v86_memory_version_exists = 0,
    'ALTER TABLE `ai_agent_memory` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 1 COMMENT ''乐观锁版本'' AFTER `content`',
    'SELECT 1'
);
PREPARE v86_memory_version_stmt FROM @v86_memory_version_ddl;
EXECUTE v86_memory_version_stmt;
DEALLOCATE PREPARE v86_memory_version_stmt;
