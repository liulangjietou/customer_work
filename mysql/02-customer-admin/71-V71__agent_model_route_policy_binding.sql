SET NAMES utf8mb4;

-- Agent 与不可变模型路由策略的显式绑定。允许为空以兼容既有主备模型链；
-- 不加外键，避免逻辑删除策略与历史配置快照互相阻塞。
ALTER TABLE `ai_agent`
    ADD COLUMN `model_route_policy_id` BIGINT DEFAULT NULL
        COMMENT '绑定的 ai_model_route_policy.id；空=沿用主备模型链' AFTER `model_id`,
    ADD KEY `idx_ai_agent_route_policy` (`tenant_id`, `model_route_policy_id`, `status`);
