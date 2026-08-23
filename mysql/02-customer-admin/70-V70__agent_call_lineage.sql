-- Admin 工作台调用日志与客服端共用同一谱系契约，支持 trace 关联与只读重放清单。
ALTER TABLE `cw_agent_call_log`
    ADD COLUMN `trace_id` VARCHAR(32) DEFAULT NULL COMMENT 'W3C trace-id，关联 OTel/Tempo' AFTER `model_reported_ms`,
    ADD COLUMN `runtime_revision` VARCHAR(64) DEFAULT NULL COMMENT '实例实际应用的运行配置发布修订' AFTER `trace_id`,
    ADD COLUMN `runtime_content_hash` CHAR(64) DEFAULT NULL COMMENT '运行配置内容摘要，关联发布任务与实例ACK' AFTER `runtime_revision`,
    ADD COLUMN `version_binding_json` JSON DEFAULT NULL COMMENT '模型/提示词/Agent/知识库/工具版本绑定（不含密钥）' AFTER `runtime_content_hash`,
    ADD INDEX `idx_call_trace_id` (`trace_id`),
    ADD INDEX `idx_call_runtime_revision` (`runtime_revision`);
