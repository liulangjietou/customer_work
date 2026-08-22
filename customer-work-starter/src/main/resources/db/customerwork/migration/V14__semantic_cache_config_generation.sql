-- 语义缓存绑定实际运行配置代际，阻断旧模型请求在热切换后回写并被新配置命中。
ALTER TABLE `cw_semantic_cache`
    ADD COLUMN `config_generation` VARCHAR(64) NOT NULL DEFAULT 'bootstrap'
        COMMENT '写入时运行配置 contentHash；bootstrap 表示尚未接入热配置' AFTER `scope_id`,
    DROP INDEX `idx_semcache_lookup`,
    DROP INDEX `idx_semcache_created`,
    ADD INDEX `idx_semcache_lookup`
        (`tenant_id`, `config_generation`, `scope_id`, `intent`, `last_hit_at_ms`),
    ADD INDEX `idx_semcache_created`
        (`tenant_id`, `config_generation`, `scope_id`, `created_at_ms`);
