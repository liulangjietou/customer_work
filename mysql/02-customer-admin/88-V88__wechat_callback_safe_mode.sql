-- 微信公众号回调由明文兼容模式升级为可配置的 AES 安全模式。
-- EncodingAESKey 与 AppSecret 一样只存 AES-GCM 密文，由可信 customer-channel 开放 API 解密使用。

SET NAMES utf8mb4;

SET @v88_table_exists = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = 'ai_channel_robot');
SET @v88_preflight_sql = IF(@v88_table_exists = 1, 'SELECT 1',
    'SELECT * FROM `__customer_admin_v88_ai_channel_robot_required__`');
PREPARE v88_preflight_stmt FROM @v88_preflight_sql;
EXECUTE v88_preflight_stmt;
DEALLOCATE PREPARE v88_preflight_stmt;

SET @v88_callback_mode_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_channel_robot' AND column_name = 'callback_mode');
SET @v88_callback_mode_ddl = IF(@v88_callback_mode_exists = 0,
    'ALTER TABLE `ai_channel_robot` ADD COLUMN `callback_mode` VARCHAR(16) NOT NULL DEFAULT ''plaintext'' COMMENT ''微信回调模式：plaintext 明文 / safe AES 安全模式'' AFTER `robot_code`',
    'SELECT 1');
PREPARE v88_callback_mode_stmt FROM @v88_callback_mode_ddl;
EXECUTE v88_callback_mode_stmt;
DEALLOCATE PREPARE v88_callback_mode_stmt;

SET @v88_encoding_key_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_channel_robot' AND column_name = 'encoding_aes_key_cipher');
SET @v88_encoding_key_ddl = IF(@v88_encoding_key_exists = 0,
    'ALTER TABLE `ai_channel_robot` ADD COLUMN `encoding_aes_key_cipher` VARCHAR(512) DEFAULT NULL COMMENT ''微信 EncodingAESKey（AES-GCM 密文）'' AFTER `callback_mode`',
    'SELECT 1');
PREPARE v88_encoding_key_stmt FROM @v88_encoding_key_ddl;
EXECUTE v88_encoding_key_stmt;
DEALLOCATE PREPARE v88_encoding_key_stmt;
