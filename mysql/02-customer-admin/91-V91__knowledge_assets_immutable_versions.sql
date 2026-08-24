-- KB/Skill 不可变版本、文档源增量同步、lineage、ACL、新鲜度和质量事实。
-- MySQL DDL 不可事务回滚：ALTER 按实际状态生成，CREATE/回填均幂等，支持 repair 后重试。

SET NAMES utf8mb4;

SET @v91_table_count = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
      AND table_name IN ('ai_knowledge_base', 'ai_agent_knowledge_base', 'ai_skill',
                         'ai_skill_file', 'ai_agent_skill', 'sys_permission', 'sys_role_permission'));
SET @v91_preflight_sql = IF(@v91_table_count = 7, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v91_required_tables_missing__`');
PREPARE v91_preflight_stmt FROM @v91_preflight_sql;
EXECUTE v91_preflight_stmt;
DEALLOCATE PREPARE v91_preflight_stmt;

SET @v91_kb_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_knowledge_base' AND column_name = 'current_version_id'), '',
       ', ADD COLUMN `current_version_id` BIGINT DEFAULT NULL COMMENT ''当前不可变版本ID'' AFTER `remark`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_knowledge_base' AND column_name = 'latest_version_no'), '',
       ', ADD COLUMN `latest_version_no` INT NOT NULL DEFAULT 0 COMMENT ''最新版本号'' AFTER `current_version_id`')
);
SET @v91_kb_column_ddl = IF(@v91_kb_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_knowledge_base` ', SUBSTRING(@v91_kb_columns, 3)));
PREPARE v91_kb_column_stmt FROM @v91_kb_column_ddl;
EXECUTE v91_kb_column_stmt;
DEALLOCATE PREPARE v91_kb_column_stmt;

SET @v91_skill_columns = CONCAT(
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_skill' AND column_name = 'current_version_id'), '',
       ', ADD COLUMN `current_version_id` BIGINT DEFAULT NULL COMMENT ''当前不可变版本ID'' AFTER `status`'),
    IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_skill' AND column_name = 'latest_version_no'), '',
       ', ADD COLUMN `latest_version_no` INT NOT NULL DEFAULT 0 COMMENT ''最新版本号'' AFTER `current_version_id`')
);
SET @v91_skill_column_ddl = IF(@v91_skill_columns = '', 'SELECT 1',
    CONCAT('ALTER TABLE `ai_skill` ', SUBSTRING(@v91_skill_columns, 3)));
PREPARE v91_skill_column_stmt FROM @v91_skill_column_ddl;
EXECUTE v91_skill_column_stmt;
DEALLOCATE PREPARE v91_skill_column_stmt;

SET @v91_agent_kb_column_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_knowledge_base'
      AND column_name = 'knowledge_base_version_id'), 'SELECT 1',
    'ALTER TABLE `ai_agent_knowledge_base` ADD COLUMN `knowledge_base_version_id` BIGINT DEFAULT NULL COMMENT ''Agent 冻结的知识库版本ID'' AFTER `knowledge_base_id`');
PREPARE v91_agent_kb_column_stmt FROM @v91_agent_kb_column_sql;
EXECUTE v91_agent_kb_column_stmt;
DEALLOCATE PREPARE v91_agent_kb_column_stmt;

SET @v91_agent_skill_column_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_skill'
      AND column_name = 'skill_version_id'), 'SELECT 1',
    'ALTER TABLE `ai_agent_skill` ADD COLUMN `skill_version_id` BIGINT DEFAULT NULL COMMENT ''Agent 冻结的 Skill 版本ID'' AFTER `skill_id`');
PREPARE v91_agent_skill_column_stmt FROM @v91_agent_skill_column_sql;
EXECUTE v91_agent_skill_column_stmt;
DEALLOCATE PREPARE v91_agent_skill_column_stmt;

CREATE TABLE IF NOT EXISTS `ai_skill_version` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `skill_id` BIGINT NOT NULL COMMENT '稳定 Skill ID',
    `version_no` INT NOT NULL COMMENT '版本号',
    `skill_name` VARCHAR(64) NOT NULL COMMENT '版本冻结名称',
    `skill_code` VARCHAR(64) NOT NULL COMMENT '版本冻结编码',
    `content` LONGTEXT NOT NULL COMMENT '版本冻结 SKILL.md',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '版本冻结描述',
    `content_hash` CHAR(64) NOT NULL COMMENT '版本内容指纹',
    `change_note` VARCHAR(255) DEFAULT NULL COMMENT '变更说明',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    UNIQUE KEY `uk_ai_skill_version_no` (`tenant_id`, `skill_id`, `version_no`),
    KEY `idx_ai_skill_version_skill` (`tenant_id`, `skill_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 不可变版本';

CREATE TABLE IF NOT EXISTS `ai_skill_version_file` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `skill_version_id` BIGINT NOT NULL COMMENT 'Skill 版本ID',
    `file_path` VARCHAR(512) NOT NULL COMMENT '相对文件路径',
    `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `content` LONGBLOB COMMENT '文件内容',
    `content_hash` CHAR(64) NOT NULL COMMENT '文件内容指纹',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    UNIQUE KEY `uk_ai_skill_version_file` (`skill_version_id`, `file_path`),
    KEY `idx_ai_skill_version_file_tenant` (`tenant_id`, `skill_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 版本附属文件';

CREATE TABLE IF NOT EXISTS `ai_knowledge_base_version` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `knowledge_base_id` BIGINT NOT NULL COMMENT '稳定知识库ID',
    `version_no` INT NOT NULL COMMENT '版本号',
    `base_url` VARCHAR(255) NOT NULL COMMENT '版本冻结 RAG 服务基址',
    `app_id` VARCHAR(128) NOT NULL COMMENT '版本冻结应用ID',
    `api_key` VARCHAR(512) NOT NULL COMMENT '版本冻结密文 AppKey',
    `content_type` VARCHAR(64) NOT NULL DEFAULT 'application/json' COMMENT '版本冻结 Content-Type',
    `extra_headers` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '版本冻结请求头',
    `top_n` INT NOT NULL DEFAULT 5 COMMENT '版本冻结召回数',
    `score_threshold` DECIMAL(8,6) NOT NULL DEFAULT 0.000000 COMMENT '版本冻结相关度阈值',
    `checkpoint` VARCHAR(512) DEFAULT NULL COMMENT '文档快照 checkpoint',
    `snapshot_hash` CHAR(64) NOT NULL COMMENT '配置与文档成员指纹',
    `document_count` INT NOT NULL DEFAULT 0 COMMENT '快照文档数',
    `quality_score` DECIMAL(8,6) NOT NULL DEFAULT 1.000000 COMMENT '快照质量分',
    `quality_status` VARCHAR(24) NOT NULL DEFAULT 'PASSED' COMMENT 'UNKNOWN/PASSED/FAILED',
    `change_note` VARCHAR(255) DEFAULT NULL COMMENT '变更说明',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    UNIQUE KEY `uk_ai_kb_version_no` (`tenant_id`, `knowledge_base_id`, `version_no`),
    KEY `idx_ai_kb_version_base` (`tenant_id`, `knowledge_base_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库不可变版本';

CREATE TABLE IF NOT EXISTS `ai_knowledge_source` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `knowledge_base_id` BIGINT NOT NULL COMMENT '知识库ID',
    `source_code` VARCHAR(128) NOT NULL COMMENT '文档源稳定编码',
    `source_name` VARCHAR(128) NOT NULL COMMENT '文档源名称',
    `source_type` VARCHAR(24) NOT NULL DEFAULT 'PUSH' COMMENT '接入类型',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0禁用/1启用',
    `freshness_sla_minutes` INT NOT NULL DEFAULT 1440 COMMENT '新鲜度 SLA 分钟',
    `quality_threshold` DECIMAL(8,6) NOT NULL DEFAULT 0.800000 COMMENT '最低质量分',
    `default_acl_json` TEXT NOT NULL COMMENT '默认文档 ACL',
    `current_checkpoint` VARCHAR(512) DEFAULT NULL COMMENT '最近成功 checkpoint',
    `last_sync_at` DATETIME(6) DEFAULT NULL COMMENT '最近同步时间',
    `last_successful_sync_at` DATETIME(6) DEFAULT NULL COMMENT '最近成功同步时间',
    `last_sync_status` VARCHAR(24) DEFAULT NULL COMMENT 'PROCESSING/SUCCEEDED/FAILED/QUALITY_FAILED',
    `last_sync_error` VARCHAR(1000) DEFAULT NULL COMMENT '最近同步错误',
    `active_document_count` INT NOT NULL DEFAULT 0 COMMENT '有效文档数',
    `quality_score` DECIMAL(8,6) DEFAULT NULL COMMENT '最近质量分',
    `quality_status` VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/PASSED/FAILED',
    `revision` INT NOT NULL DEFAULT 1 COMMENT '配置修订号',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `update_by` BIGINT DEFAULT NULL COMMENT '更新人ID',
    `update_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY `uk_ai_kb_source_code` (`tenant_id`, `knowledge_base_id`, `source_code`),
    KEY `idx_ai_kb_source_base` (`tenant_id`, `knowledge_base_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档源';

CREATE TABLE IF NOT EXISTS `ai_knowledge_document` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `knowledge_base_id` BIGINT NOT NULL COMMENT '知识库ID',
    `source_id` BIGINT NOT NULL COMMENT '文档源ID',
    `external_id` VARCHAR(512) NOT NULL COMMENT '上游文档稳定ID',
    `current_revision_id` BIGINT DEFAULT NULL COMMENT '当前修订ID',
    `source_version` VARCHAR(255) DEFAULT NULL COMMENT '上游版本',
    `content_hash` CHAR(64) DEFAULT NULL COMMENT '当前正文指纹',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '同步删除标记',
    `source_updated_at` DATETIME(6) DEFAULT NULL COMMENT '上游更新时间',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `update_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    UNIQUE KEY `uk_ai_kb_document_external` (`tenant_id`, `source_id`, `external_id`),
    KEY `idx_ai_kb_document_active` (`tenant_id`, `knowledge_base_id`, `deleted`, `source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档稳定身份';

CREATE TABLE IF NOT EXISTS `ai_knowledge_document_revision` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `document_id` BIGINT NOT NULL COMMENT '文档稳定ID',
    `source_id` BIGINT NOT NULL COMMENT '文档源ID',
    `parent_revision_id` BIGINT DEFAULT NULL COMMENT '父修订ID',
    `operation` VARCHAR(16) NOT NULL COMMENT 'UPSERT/DELETE',
    `source_version` VARCHAR(255) DEFAULT NULL COMMENT '上游版本',
    `title` VARCHAR(512) DEFAULT NULL COMMENT '标题',
    `source_uri` VARCHAR(2048) DEFAULT NULL COMMENT '来源地址',
    `content` LONGTEXT COMMENT '不可变正文；DELETE 修订为空',
    `content_hash` CHAR(64) DEFAULT NULL COMMENT '正文指纹',
    `acl_mode` VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/RESTRICTED',
    `allowed_subject_types` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '允许主体类型',
    `allowed_subject_ids` TEXT NOT NULL COMMENT '允许主体ID JSON数组',
    `allowed_channels` TEXT NOT NULL COMMENT '允许渠道 JSON数组',
    `source_updated_at` DATETIME(6) DEFAULT NULL COMMENT '上游更新时间',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    KEY `idx_ai_kb_revision_document` (`tenant_id`, `document_id`, `id`),
    KEY `idx_ai_kb_revision_source` (`tenant_id`, `source_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档不可变修订';

CREATE TABLE IF NOT EXISTS `ai_knowledge_document_chunk` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `document_revision_id` BIGINT NOT NULL COMMENT '文档修订ID',
    `chunk_index` INT NOT NULL COMMENT '分块序号',
    `content` LONGTEXT NOT NULL COMMENT '分块正文',
    `embedding` LONGTEXT NOT NULL COMMENT '向量 JSON',
    `dimensions` INT NOT NULL COMMENT '向量维度',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    UNIQUE KEY `uk_ai_kb_chunk_index` (`document_revision_id`, `chunk_index`),
    KEY `idx_ai_kb_chunk_tenant_revision` (`tenant_id`, `document_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档向量分块';

CREATE TABLE IF NOT EXISTS `ai_knowledge_base_version_document` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `knowledge_base_version_id` BIGINT NOT NULL COMMENT '知识库版本ID',
    `document_revision_id` BIGINT NOT NULL COMMENT '文档修订ID',
    `source_id` BIGINT NOT NULL COMMENT '文档源ID',
    `external_id` VARCHAR(512) NOT NULL COMMENT '文档稳定外部ID',
    UNIQUE KEY `uk_ai_kb_version_document` (`knowledge_base_version_id`, `source_id`, `external_id`),
    KEY `idx_ai_kb_version_revision` (`tenant_id`, `document_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库版本文档成员';

CREATE TABLE IF NOT EXISTS `ai_knowledge_sync_run` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `knowledge_base_id` BIGINT NOT NULL COMMENT '知识库ID',
    `source_id` BIGINT NOT NULL COMMENT '文档源ID',
    `request_id` VARCHAR(128) NOT NULL COMMENT '上游幂等请求ID',
    `request_hash` CHAR(64) NOT NULL COMMENT '请求内容指纹',
    `sync_mode` VARCHAR(16) NOT NULL COMMENT 'FULL/INCREMENTAL',
    `checkpoint_before` VARCHAR(512) DEFAULT NULL COMMENT '提交前 checkpoint',
    `checkpoint_after` VARCHAR(512) NOT NULL COMMENT '目标 checkpoint',
    `status` VARCHAR(24) NOT NULL COMMENT 'PROCESSING/SUCCEEDED/FAILED/QUALITY_FAILED',
    `received_count` INT NOT NULL DEFAULT 0 COMMENT '接收变更数',
    `upserted_count` INT DEFAULT NULL COMMENT '写入数',
    `deleted_count` INT DEFAULT NULL COMMENT '删除数',
    `unchanged_count` INT DEFAULT NULL COMMENT '未变化数',
    `active_document_count` INT DEFAULT NULL COMMENT '提交后有效文档数',
    `duplicate_content_count` INT DEFAULT NULL COMMENT '重复正文数',
    `quality_score` DECIMAL(8,6) DEFAULT NULL COMMENT '质量分',
    `quality_status` VARCHAR(24) DEFAULT NULL COMMENT 'UNKNOWN/PASSED/FAILED',
    `knowledge_base_version_id` BIGINT DEFAULT NULL COMMENT '成功发布的知识库版本ID',
    `snapshot_hash` CHAR(64) DEFAULT NULL COMMENT '成功快照指纹',
    `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '失败摘要',
    `started_at` DATETIME(6) NOT NULL COMMENT '开始时间',
    `finished_at` DATETIME(6) DEFAULT NULL COMMENT '结束时间',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    UNIQUE KEY `uk_ai_kb_sync_request` (`tenant_id`, `source_id`, `request_id`),
    KEY `idx_ai_kb_sync_runs` (`tenant_id`, `source_id`, `id`),
    KEY `idx_ai_kb_sync_status` (`tenant_id`, `status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档源同步运行';

INSERT INTO `ai_skill_version`
    (`tenant_id`, `skill_id`, `version_no`, `skill_name`, `skill_code`, `content`, `description`,
     `content_hash`, `change_note`, `create_by`, `create_time`)
SELECT `s`.`tenant_id`, `s`.`id`, 1, `s`.`skill_name`, `s`.`skill_code`, `s`.`content`, `s`.`description`,
       SHA2(CONCAT('skill-v1|', `s`.`skill_code`, '|', `s`.`content`), 256),
       'V91 存量 Skill 基线', `s`.`create_by`, `s`.`create_time`
FROM `ai_skill` `s`
WHERE NOT EXISTS (SELECT 1 FROM `ai_skill_version` `v`
    WHERE `v`.`tenant_id` = `s`.`tenant_id` AND `v`.`skill_id` = `s`.`id` AND `v`.`version_no` = 1);

INSERT INTO `ai_skill_version_file`
    (`tenant_id`, `skill_version_id`, `file_path`, `file_size`, `content`, `content_hash`, `create_time`)
SELECT `f`.`tenant_id`, `v`.`id`, `f`.`file_path`, `f`.`file_size`, `f`.`content`,
       SHA2(COALESCE(`f`.`content`, ''), 256), `f`.`create_time`
FROM `ai_skill_file` `f`
JOIN `ai_skill_version` `v`
  ON `v`.`tenant_id` = `f`.`tenant_id` AND `v`.`skill_id` = `f`.`skill_id` AND `v`.`version_no` = 1
WHERE NOT EXISTS (SELECT 1 FROM `ai_skill_version_file` `vf`
    WHERE `vf`.`skill_version_id` = `v`.`id` AND `vf`.`file_path` = `f`.`file_path`);

UPDATE `ai_skill` `s`
JOIN `ai_skill_version` `v`
  ON `v`.`tenant_id` = `s`.`tenant_id` AND `v`.`skill_id` = `s`.`id` AND `v`.`version_no` = 1
SET `s`.`current_version_id` = COALESCE(`s`.`current_version_id`, `v`.`id`),
    `s`.`latest_version_no` = GREATEST(`s`.`latest_version_no`, 1);

INSERT INTO `ai_knowledge_base_version`
    (`tenant_id`, `knowledge_base_id`, `version_no`, `base_url`, `app_id`, `api_key`, `content_type`,
     `extra_headers`, `top_n`, `score_threshold`, `snapshot_hash`, `document_count`, `quality_score`,
     `quality_status`, `change_note`, `create_by`, `create_time`)
SELECT `k`.`tenant_id`, `k`.`id`, 1, `k`.`base_url`, `k`.`app_id`, `k`.`api_key`, `k`.`content_type`,
       `k`.`extra_headers`, `k`.`top_n`, `k`.`score_threshold`,
       SHA2(CONCAT('knowledge-base-v1|', `k`.`id`, '|', `k`.`base_url`, '|', `k`.`app_id`, '|',
                   `k`.`api_key`, '|', `k`.`top_n`, '|', `k`.`score_threshold`), 256),
       0, 1.000000, 'PASSED', 'V91 存量知识库基线', `k`.`create_by`, `k`.`create_time`
FROM `ai_knowledge_base` `k`
WHERE NOT EXISTS (SELECT 1 FROM `ai_knowledge_base_version` `v`
    WHERE `v`.`tenant_id` = `k`.`tenant_id` AND `v`.`knowledge_base_id` = `k`.`id`
      AND `v`.`version_no` = 1);

UPDATE `ai_knowledge_base` `k`
JOIN `ai_knowledge_base_version` `v`
  ON `v`.`tenant_id` = `k`.`tenant_id` AND `v`.`knowledge_base_id` = `k`.`id` AND `v`.`version_no` = 1
SET `k`.`current_version_id` = COALESCE(`k`.`current_version_id`, `v`.`id`),
    `k`.`latest_version_no` = GREATEST(`k`.`latest_version_no`, 1);

UPDATE `ai_agent_skill` `r`
JOIN `ai_skill` `s` ON `s`.`id` = `r`.`skill_id` AND `s`.`tenant_id` = `r`.`tenant_id`
SET `r`.`skill_version_id` = `s`.`current_version_id`
WHERE `r`.`skill_version_id` IS NULL;

UPDATE `ai_agent_knowledge_base` `r`
JOIN `ai_knowledge_base` `k`
  ON `k`.`id` = `r`.`knowledge_base_id` AND `k`.`tenant_id` = `r`.`tenant_id`
SET `r`.`knowledge_base_version_id` = `k`.`current_version_id`
WHERE `r`.`knowledge_base_version_id` IS NULL;

SET @v91_unversioned_relations =
    (SELECT COUNT(*) FROM `ai_agent_skill` WHERE `skill_version_id` IS NULL)
    + (SELECT COUNT(*) FROM `ai_agent_knowledge_base` WHERE `knowledge_base_version_id` IS NULL);
SET @v91_relation_preflight_sql = IF(@v91_unversioned_relations = 0, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v91_orphan_agent_asset_relation__`');
PREPARE v91_relation_preflight_stmt FROM @v91_relation_preflight_sql;
EXECUTE v91_relation_preflight_stmt;
DEALLOCATE PREPARE v91_relation_preflight_stmt;

SET @v91_agent_skill_nullable = (SELECT `IS_NULLABLE` = 'YES' FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_skill' AND column_name = 'skill_version_id');
SET @v91_agent_skill_not_null_sql = IF(@v91_agent_skill_nullable = 1,
    'ALTER TABLE `ai_agent_skill` MODIFY COLUMN `skill_version_id` BIGINT NOT NULL COMMENT ''Agent 冻结的 Skill 版本ID''',
    'SELECT 1');
PREPARE v91_agent_skill_not_null_stmt FROM @v91_agent_skill_not_null_sql;
EXECUTE v91_agent_skill_not_null_stmt;
DEALLOCATE PREPARE v91_agent_skill_not_null_stmt;

SET @v91_agent_kb_nullable = (SELECT `IS_NULLABLE` = 'YES' FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_knowledge_base'
      AND column_name = 'knowledge_base_version_id');
SET @v91_agent_kb_not_null_sql = IF(@v91_agent_kb_nullable = 1,
    'ALTER TABLE `ai_agent_knowledge_base` MODIFY COLUMN `knowledge_base_version_id` BIGINT NOT NULL COMMENT ''Agent 冻结的知识库版本ID''',
    'SELECT 1');
PREPARE v91_agent_kb_not_null_stmt FROM @v91_agent_kb_not_null_sql;
EXECUTE v91_agent_kb_not_null_stmt;
DEALLOCATE PREPARE v91_agent_kb_not_null_stmt;

SET @v91_agent_skill_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_skill'
      AND index_name = 'idx_ai_agent_skill_version'), 'SELECT 1',
    'ALTER TABLE `ai_agent_skill` ADD INDEX `idx_ai_agent_skill_version` (`tenant_id`, `skill_version_id`)');
PREPARE v91_agent_skill_index_stmt FROM @v91_agent_skill_index_sql;
EXECUTE v91_agent_skill_index_stmt;
DEALLOCATE PREPARE v91_agent_skill_index_stmt;

SET @v91_agent_kb_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_knowledge_base'
      AND index_name = 'idx_ai_agent_kb_version'), 'SELECT 1',
    'ALTER TABLE `ai_agent_knowledge_base` ADD INDEX `idx_ai_agent_kb_version` (`tenant_id`, `knowledge_base_version_id`)');
PREPARE v91_agent_kb_index_stmt FROM @v91_agent_kb_index_sql;
EXECUTE v91_agent_kb_index_stmt;
DEALLOCATE PREPARE v91_agent_kb_index_stmt;

INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`)
SELECT `p`.`id`, '同步知识文档源', 'knowledge-base:source-sync', 2, 4
FROM `sys_permission` `p`
WHERE `p`.`perm_code` = 'knowledge-base:view'
  AND NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `perm_code` = 'knowledge-base:source-sync');

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`, `tenant_id`)
SELECT DISTINCT `rp`.`role_id`, `sync_permission`.`id`, `rp`.`tenant_id`
FROM `sys_role_permission` `rp`
JOIN `sys_permission` `edit_permission`
  ON `edit_permission`.`id` = `rp`.`permission_id`
 AND `edit_permission`.`perm_code` = 'knowledge-base:edit'
JOIN `sys_permission` `sync_permission`
  ON `sync_permission`.`perm_code` = 'knowledge-base:source-sync'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_role_permission` `existing_grant`
    WHERE `existing_grant`.`role_id` = `rp`.`role_id`
      AND `existing_grant`.`permission_id` = `sync_permission`.`id`
      AND `existing_grant`.`tenant_id` = `rp`.`tenant_id`
);
