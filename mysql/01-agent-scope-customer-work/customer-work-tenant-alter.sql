-- 首行强制 utf8mb4，避免走 stdin 管道时客户端字符集回退 latin1 把中文 COMMENT 字节级写坏（见 mysql/README.md）
SET NAMES utf8mb4;

-- ============================================================================
-- 增量迁移：全部 cw_* 业务表增加 tenant_id（多租户行级隔离地基，B1 批次）
-- 适用场景：已部署的 agent_scope_customer_work 库增量升级
--          （全新建库直接跑 customer-work-schema.sql 即含租户列）。
-- 存量数据：tenant_id 默认 'default'，即升级前的单租户系统整体归入 default 租户，无需数据搬迁。
-- 幂等：加列/加索引走 information_schema 判定；唯一键重建先判存在再 DROP，可重复执行。
--
-- 为什么用游标遍历而不是逐表写 27 段 ALTER：cw_* 表还会增加，遍历式迁移对新表天然生效，
-- 且避免了 54 段近乎重复的 PREPARE 块——那种长度反而让漏改某张表变得难以察觉。
-- ============================================================================

DROP PROCEDURE IF EXISTS `cw_add_tenant_column`;

DELIMITER $$
CREATE PROCEDURE `cw_add_tenant_column`()
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_table VARCHAR(64);
    DECLARE cur CURSOR FOR
        SELECT TABLE_NAME FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'cw\_%';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_table;
        IF v_done = 1 THEN
            LEAVE read_loop;
        END IF;

        -- 加租户列（已存在则跳过）
        SET @col_exists := (
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = v_table AND COLUMN_NAME = 'tenant_id');
        IF @col_exists = 0 THEN
            SET @ddl := CONCAT('ALTER TABLE `', v_table,
                '` ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT ''default'' COMMENT ''租户ID（多租户行级隔离）''');
            PREPARE stmt FROM @ddl;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;

        -- 加租户索引（已存在则跳过）；索引名与 customer-work-schema.sql 保持一致
        SET @idx_name := CONCAT('idx_', SUBSTRING(v_table, 4), '_tenant');
        SET @idx_exists := (
            SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = v_table AND INDEX_NAME = @idx_name);
        IF @idx_exists = 0 THEN
            SET @ddl := CONCAT('ALTER TABLE `', v_table, '` ADD INDEX `', @idx_name, '` (`tenant_id`)');
            PREPARE stmt FROM @ddl;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;

CALL `cw_add_tenant_column`();
DROP PROCEDURE `cw_add_tenant_column`;

-- ============================================================================
-- 唯一键重建：原先全局唯一的业务标识改为租户内唯一，否则两个租户无法使用相同用户名/词条/字典键。
-- cw_chat_message.uk_chat_message_id 刻意不动：它是框架 Msg.id 的 UUID，本就全局唯一。
-- ============================================================================

DROP PROCEDURE IF EXISTS `cw_rebuild_tenant_unique_key`;

DELIMITER $$
CREATE PROCEDURE `cw_rebuild_tenant_unique_key`(
    IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns VARCHAR(255))
BEGIN
    -- 已含 tenant_id 的唯一键说明本脚本跑过了，直接跳过
    SET @already := (
        SELECT COUNT(*) FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index AND COLUMN_NAME = 'tenant_id');
    IF @already = 0 THEN
        SET @exists := (
            SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index);
        IF @exists > 0 THEN
            SET @ddl := CONCAT('ALTER TABLE `', p_table, '` DROP INDEX `', p_index, '`');
            PREPARE stmt FROM @ddl;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
        SET @ddl := CONCAT('ALTER TABLE `', p_table, '` ADD UNIQUE KEY `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL `cw_rebuild_tenant_unique_key`('cw_user', 'uk_user_username', '`tenant_id`, `username`');
CALL `cw_rebuild_tenant_unique_key`('cw_knowledge', 'uk_knowledge_title', '`tenant_id`, `title`');
CALL `cw_rebuild_tenant_unique_key`('cw_sensitive_word', 'uk_sensitive_word', '`tenant_id`, `word`');
CALL `cw_rebuild_tenant_unique_key`('cw_rate_limit_rule', 'uk_rate_limit_rule_name', '`tenant_id`, `rule_name`');
CALL `cw_rebuild_tenant_unique_key`('cw_dict_type', 'uk_dict_type', '`tenant_id`, `dict_type`');
CALL `cw_rebuild_tenant_unique_key`('cw_dict_item', 'uk_dict_item', '`tenant_id`, `dict_type`, `item_key`');

DROP PROCEDURE `cw_rebuild_tenant_unique_key`;
