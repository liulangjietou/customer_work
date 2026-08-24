-- V57 未显式指定排序规则，在 MySQL 8 默认库上会继承 utf8mb4_0900_ai_ci；
-- 后续实验、路由与 Agent 表统一使用 utf8mb4_unicode_ci，跨表比较任务 ID、租户 ID 时会触发 1267。
-- 两张运行时投递表存在 revision/content_hash 等关联字段，统一整表而非只修当前报错列，避免继续留下隐患。
ALTER TABLE `ai_runtime_publish_task`
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE `ai_runtime_config_ack`
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
