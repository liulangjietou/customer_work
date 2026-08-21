-- V7 已将历史平台租户行的 tenant_id 归一为 default；本迁移补齐仍承载旧租户分区语义的 scope_id。
--
-- cw_long_term_memory.scope_hash 的应用契约是 SHA-256(UTF-8(scope_id + '\n' + fact))。
-- scope_id 变化时必须同步重算；所有目标唯一键冲突均在业务表写入前中止，禁止静默丢数据。

-- 迁移若曾因数据冲突失败，同一连接上的临时表可能仍然存在；先清理以允许修复数据后重试。
DROP TEMPORARY TABLE IF EXISTS `_v8_ltm_conflict_guard`;
DROP TEMPORARY TABLE IF EXISTS `_v8_ltm_scope_rehash`;

CREATE TEMPORARY TABLE `_v8_ltm_scope_rehash` (
    `id` BIGINT NOT NULL,
    `new_scope_hash` VARBINARY(64) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_v8_ltm_new_scope_hash` (`new_scope_hash`)
) ENGINE=InnoDB;

-- CHAR(10) 明确表达 Java 哈希契约里的换行符，避免依赖 MySQL 反斜线转义模式。
-- 映射表的唯一键同时预检“两个待迁移源行归一后撞 hash”。
INSERT INTO `_v8_ltm_scope_rehash` (`id`, `new_scope_hash`)
SELECT `id`, LOWER(SHA2(CONCAT('default', CHAR(10), `fact`), 256))
FROM `cw_long_term_memory`
WHERE `tenant_id` = 'default'
  AND `scope_id` = '__platform__';

CREATE TEMPORARY TABLE `_v8_ltm_conflict_guard` (
    `singleton` TINYINT NOT NULL,
    UNIQUE KEY `uk_v8_ltm_conflict_guard` (`singleton`)
) ENGINE=InnoDB;

INSERT INTO `_v8_ltm_conflict_guard` (`singleton`) VALUES (1);

-- 预检待迁移行的新 hash 与任意其它 default 存量行的当前 hash 是否冲突。
-- “其它行”包含另一条待迁移源行，因此后续可以用一条 UPDATE 原子改写 scope/hash，不遗留中间 hash。
-- 命中时再次插入固定值 1，由唯一键在任何业务表写入前中止迁移。
INSERT INTO `_v8_ltm_conflict_guard` (`singleton`)
SELECT 1
FROM `_v8_ltm_scope_rehash` `m`
JOIN `cw_long_term_memory` `d`
  ON `d`.`tenant_id` = 'default'
 AND BINARY LOWER(`d`.`scope_hash`) = `m`.`new_scope_hash`
WHERE `d`.`id` <> `m`.`id`
LIMIT 1;

DROP TEMPORARY TABLE `_v8_ltm_conflict_guard`;

UPDATE `cw_long_term_memory` `l`
JOIN `_v8_ltm_scope_rehash` `m` ON `m`.`id` = `l`.`id`
SET `l`.`scope_id` = 'default',
    `l`.`scope_hash` = `m`.`new_scope_hash`;

UPDATE `cw_fact_log`
SET `scope_id` = 'default'
WHERE `tenant_id` = 'default'
  AND `scope_id` = '__platform__';

UPDATE `cw_semantic_cache`
SET `scope_id` = 'default'
WHERE `tenant_id` = 'default'
  AND `scope_id` = '__platform__';

DROP TEMPORARY TABLE `_v8_ltm_scope_rehash`;
