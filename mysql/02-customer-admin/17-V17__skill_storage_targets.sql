-- =============================================================================
-- Skill 保存/上传支持多存储目标（本地 workspace / Nacos / SFTP）（Flyway V17，仅本地/测试 profile 自动执行）
-- =============================================================================
-- ai_skill 除入库外，需把 SKILL.md 正文发布到用户勾选的存储目标。新增 storage_targets 列记录
-- 每个 skill 独立勾选的目标（逗号分隔），删除/取消勾选时据此做远端清理。历史行默认 local。
-- =============================================================================

ALTER TABLE `ai_skill`
    ADD COLUMN `storage_targets` VARCHAR(64) NOT NULL DEFAULT 'local'
        COMMENT '存储目标，逗号分隔：local,nacos,sftp';
