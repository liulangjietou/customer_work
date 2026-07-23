-- =============================================================================
-- Skill 附属文件（Flyway V33，仅本地/测试 profile 自动执行）
-- =============================================================================
-- zip 上传的技能包除 SKILL.md 外还有 references/scripts/examples 等附属文件，此前被直接丢弃，
-- 运行时落盘只有 SKILL.md，技能里引用的脚本/参考文档全部失效。本表按 skill 存全部附属文件
-- （文本/二进制统一按字节存），构建智能体实例时与 SKILL.md 一起落盘供 FileSystemSkillRepository 加载。
-- 文件随保存全量替换，行走物理删除（不用逻辑删除，避免 LONGBLOB 死数据堆积）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_skill_file` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `skill_id`    BIGINT NOT NULL COMMENT '所属 Skill（ai_skill.id）',
    `file_path`   VARCHAR(512) NOT NULL COMMENT '相对 SKILL.md 所在目录的路径，如 references/api.md',
    `file_size`   BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `content`     LONGBLOB COMMENT '文件内容（文本/二进制统一按字节存）',
    `create_by`   BIGINT COMMENT '创建人ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_ai_skill_file_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 附属文件（zip 上传的 references/scripts 等）';
