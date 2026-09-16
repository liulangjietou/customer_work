-- 上下文压缩触发消息数列语义变更：null 不再表示"不启用压缩"，而是"使用系统默认压缩策略"。
-- 长任务型智能体（vibecoding/plan/subagent/skill-learning/dynamic-subagent/memory 任一能力）
-- 从此默认自带压缩保护，见 AdminAgentInstanceFactory#buildHarnessAgent。
-- 只改列注释说明，不改结构、不改数据。
ALTER TABLE `ai_agent`
    MODIFY COLUMN `compress_trigger_msgs` INT NULL COMMENT '上下文压缩触发消息数（null=使用系统默认压缩策略，仅当智能体具备长任务能力时生效）';
