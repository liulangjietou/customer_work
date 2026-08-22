SET NAMES utf8mb4;

-- 发布任务按 Nacos 真实外写键串行，支持 oldest/newer 自连接的范围查找。
ALTER TABLE `ai_runtime_publish_task`
    ADD KEY `idx_runtime_publish_nacos_key` (`tenant_id`, `data_id`, `group_name`, `seq`, `status`);
