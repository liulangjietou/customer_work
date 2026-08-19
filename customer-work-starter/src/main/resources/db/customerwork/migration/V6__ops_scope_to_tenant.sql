-- 运营统计分区归一：scope_id 从"sessionId 前缀"改为"租户码"。
--
-- 病根是两种 scope 混用了一个解析器：TenantResolver 的契约是"sessionId 前缀即租户"，
-- 而用户端 sessionId 形如 u{userId}:conv-xxx，前缀其实是用户。数据分区（长期记忆、
-- 语义缓存）要的正是按用户隔离，但 CSAT 与知识盲区是运营指标，按用户分区等于每人一张报表——
-- 后台看板填任何一个口径都查不出数据，而链路本身不报任何错。
--
-- 存量行的 tenant_id 一直是对的，直接拿它当新的分区键即可。
-- 两段都幂等（重跑结果相同、不会撞唯一键），故无需在 CustomerWorkSchemaMigrator#resolveBaselineVersion
-- 里补数据判定——完整镜像库上重跑只会影响 0 行。

-- ---- CSAT：主键是 session_id，改分区不涉及合并 ----
UPDATE `cw_csat_survey` SET `scope_id` = `tenant_id` WHERE `scope_id` <> `tenant_id`;

-- ---- 知识盲区：唯一键含 scope_id，同一问题散在多个用户分区下，归一前必须先合并计数 ----
-- 把同租户同问题的统计聚到 id 最小的那一行：次数求和、首次出现取最早、最近出现取最晚。
-- 直接归一而不合并会撞 uk_knowledge_gap，且"这个问题被问了多少次"本就该是跨用户的总和。
UPDATE `cw_knowledge_gap` g
JOIN (
    SELECT `tenant_id`,
           `question_hash`,
           MIN(`id`)                AS keep_id,
           SUM(`miss_count`)        AS total_miss,
           MIN(`first_seen_at_ms`)  AS first_ms,
           MAX(`last_seen_at_ms`)   AS last_ms
    FROM `cw_knowledge_gap`
    GROUP BY `tenant_id`, `question_hash`
) m ON g.`id` = m.keep_id
SET g.`miss_count`       = m.total_miss,
    g.`first_seen_at_ms` = m.first_ms,
    g.`last_seen_at_ms`  = m.last_ms;

-- 删掉被合并掉的行，使每个 (tenant_id, question_hash) 只剩一行——
-- 必须在改 scope_id 之前删干净，否则下一步会撞唯一键
DELETE g FROM `cw_knowledge_gap` g
JOIN (
    SELECT `tenant_id`, `question_hash`, MIN(`id`) AS keep_id
    FROM `cw_knowledge_gap`
    GROUP BY `tenant_id`, `question_hash`
) m ON g.`tenant_id` = m.`tenant_id` AND g.`question_hash` = m.`question_hash`
WHERE g.`id` <> m.keep_id;

UPDATE `cw_knowledge_gap` SET `scope_id` = `tenant_id` WHERE `scope_id` <> `tenant_id`;

-- ---- 列注释同步 ----
-- 注释写的仍是旧口径（"TenantResolver 由 sessionId 解析"），照着它读代码会得出相反的结论。
-- 只能在这里改：V1__baseline.sql 已在各环境应用过，动它会让 Flyway 校验 checksum 不一致而拒绝启动。
ALTER TABLE `cw_csat_survey`
    MODIFY COLUMN `scope_id` VARCHAR(128) NOT NULL DEFAULT 'default'
    COMMENT '运营统计分区键 = 租户码（OpsScopeResolver 取当前租户上下文）';

ALTER TABLE `cw_knowledge_gap`
    MODIFY COLUMN `scope_id` VARCHAR(128) NOT NULL DEFAULT 'default'
    COMMENT '运营统计分区键 = 租户码（OpsScopeResolver 取当前租户上下文）';
