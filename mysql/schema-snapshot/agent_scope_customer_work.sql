-- ----------------------------------------------------------------------------
-- agent_scope_customer_work 全量表结构快照（自动生成，请勿手工编辑）
-- ----------------------------------------------------------------------------
-- 生成方式：scripts/export-schema-snapshot.sh
--           新建临时空库执行 classpath:db/customerwork/migration 的全部迁移
--           （含 V2/V9 两个 Java 迁移）后逐表导出，自增当前值已抹除。
-- 对应版本：Flyway V21
-- 真源：customer-work-starter/src/main/resources/db/customerwork/migration/
--       + com.richard.fyoung.customerwork.infra.migration 下的 Java 迁移。
--       改结构一律新增迁移，改本文件不会生效。
-- 用途：结构查阅与全新建库。**不要对已有库执行**，这里没有 IF NOT EXISTS 保护。
-- 缺表说明：框架会话表 agentscope_sessions 不在本快照内——它由 AgentScope 的
--           MysqlSession 在首次连接时按内置 DDL 自建，不归 Flyway 管，因此也没有
--           迁移可以导出它。用本快照建库后由框架自行补上，不是遗漏。
-- 建库：CREATE DATABASE `agent_scope_customer_work`
--         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- COLLATE 说明：快照里同时出现 utf8mb4_0900_ai_ci 与 utf8mb4_unicode_ci 是既有状况，
--               不是导出错误。MySQL 8 的规则：建表语句写了 DEFAULT CHARSET=utf8mb4
--               却没写 COLLATE 时，用的是该字符集的默认 collation(utf8mb4_0900_ai_ci)
--               而非库的；显式写了 COLLATE 的按其声明；只有既不写 CHARSET 也不写
--               COLLATE 的少数表才继承上面的建库参数——所以导出必须固定按上面的参数
--               建库，换一套参数会让那几张表的输出跟着变。
-- ----------------------------------------------------------------------------

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- cw_agent_call_log
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_agent_call_log` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `request_id` varchar(64) NOT NULL DEFAULT '' COMMENT '请求ID（全链路关联）',
  `user_id` varchar(128) NOT NULL DEFAULT '' COMMENT '用户ID（ctx.userId）',
  `username` varchar(128) NOT NULL DEFAULT '' COMMENT '用户名',
  `agent_code` varchar(128) NOT NULL DEFAULT '' COMMENT '智能体编码',
  `agent_name` varchar(255) NOT NULL DEFAULT '' COMMENT '智能体名称',
  `session_id` varchar(128) NOT NULL DEFAULT '' COMMENT '会话ID',
  `session_type` varchar(32) NOT NULL DEFAULT 'CHAT' COMMENT '会话类型 CHAT/VIBE_CODING',
  `question` mediumtext COMMENT '用户问题',
  `answer` mediumtext COMMENT '智能体回答',
  `start_time` bigint NOT NULL COMMENT '调用开始时间戳（毫秒）',
  `end_time` bigint NOT NULL COMMENT '调用结束时间戳（毫秒）',
  `duration_ms` bigint NOT NULL DEFAULT '0' COMMENT '总耗时（毫秒）',
  `model_ms` bigint NOT NULL DEFAULT '0' COMMENT 'MODEL段耗时合计（毫秒）',
  `tool_ms` bigint NOT NULL DEFAULT '0' COMMENT 'TOOL段耗时合计（毫秒）',
  `mcp_ms` bigint NOT NULL DEFAULT '0' COMMENT 'MCP段耗时合计（毫秒）',
  `skill_ms` bigint NOT NULL DEFAULT '0' COMMENT 'SKILL段耗时合计（毫秒）',
  `segment_count` int NOT NULL DEFAULT '0' COMMENT '分段总数',
  `input_tokens` bigint DEFAULT NULL COMMENT '请求级输入token合计（缺失为NULL）',
  `output_tokens` bigint DEFAULT NULL COMMENT '请求级输出token合计（缺失为NULL）',
  `total_tokens` bigint DEFAULT NULL COMMENT '请求级总token合计（缺失为NULL）',
  `cached_tokens` bigint DEFAULT NULL COMMENT '命中缓存的输入token（input_tokens的子集，不计入total）',
  `model_reported_ms` bigint DEFAULT NULL COMMENT '模型自报耗时合计（毫秒），与model_ms之差=网络/排队开销',
  `model_cost_amount` decimal(30,14) DEFAULT NULL COMMENT '本次调用已结算模型金额',
  `model_cost_currency` varchar(16) DEFAULT NULL COMMENT '单币种结算币种',
  `model_cost_status` varchar(24) NOT NULL DEFAULT 'NO_MODEL' COMMENT 'COMPLETE/PARTIAL/UNAVAILABLE/MULTI_CURRENCY/NO_MODEL',
  `model_segment_count` int NOT NULL DEFAULT '0' COMMENT '模型分段数',
  `settled_cost_segment_count` int NOT NULL DEFAULT '0' COMMENT '已结算模型分段数',
  `unsettled_cost_segment_count` int NOT NULL DEFAULT '0' COMMENT '未结算模型分段数',
  `trace_id` varchar(32) DEFAULT NULL COMMENT 'W3C trace-id，关联 OTel/Tempo',
  `runtime_revision` varchar(64) DEFAULT NULL COMMENT '实例实际应用的运行配置发布修订',
  `runtime_content_hash` char(64) DEFAULT NULL COMMENT '运行配置内容摘要，关联发布任务与实例ACK',
  `version_binding_json` json DEFAULT NULL COMMENT '模型/提示词/Agent/知识库/工具版本绑定（不含密钥）',
  `replay_snapshot_json` json DEFAULT NULL COMMENT '脱敏模型参数、RAG与工具重放事实',
  `experiment_id` bigint DEFAULT NULL COMMENT '在线实验ID',
  `experiment_revision` int DEFAULT NULL COMMENT '不可变实验修订号',
  `experiment_arm` varchar(16) DEFAULT NULL COMMENT 'CONTROL/TREATMENT',
  `experiment_deployment_id` bigint DEFAULT NULL COMMENT '实际命中的模型部署ID',
  `experiment_bucket` int DEFAULT NULL COMMENT '稳定分桶0..9999；无可信主体时为空',
  `success` tinyint(1) NOT NULL DEFAULT '1' COMMENT '整次调用是否成功',
  `error_msg` varchar(1024) DEFAULT NULL COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_call_request` (`request_id`),
  KEY `idx_call_username` (`username`),
  KEY `idx_call_agent_code` (`agent_code`),
  KEY `idx_call_session` (`session_id`),
  KEY `idx_call_start` (`start_time`),
  KEY `idx_agent_call_log_tenant` (`tenant_id`),
  KEY `idx_call_trace_id` (`trace_id`),
  KEY `idx_call_runtime_revision` (`runtime_revision`),
  KEY `idx_call_experiment_arm` (`experiment_id`,`experiment_revision`,`experiment_arm`,`start_time`),
  KEY `idx_agent_call_cost_window` (`tenant_id`,`start_time`,`model_cost_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体调用主记录（分段耗时统计）';

-- ----------------------------------------------------------------------------
-- cw_agent_call_segment
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_agent_call_segment` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `call_log_id` bigint NOT NULL COMMENT '所属主记录ID',
  `seq` int NOT NULL COMMENT '调用内分段序号（从1起）',
  `kind` varchar(16) NOT NULL COMMENT '分段类别 MODEL/TOOL/MCP/SKILL',
  `name` varchar(255) NOT NULL DEFAULT '' COMMENT '分段名称（模型名/工具名）',
  `start_time` bigint NOT NULL COMMENT '分段开始时间戳（毫秒）',
  `duration_ms` bigint NOT NULL DEFAULT '0' COMMENT '分段耗时（毫秒）',
  `input_tokens` bigint DEFAULT NULL COMMENT '输入token（仅MODEL段，缺失为NULL）',
  `output_tokens` bigint DEFAULT NULL COMMENT '输出token（仅MODEL段，缺失为NULL）',
  `cached_tokens` bigint DEFAULT NULL COMMENT '命中缓存的输入token（仅MODEL段）',
  `model_reported_ms` bigint DEFAULT NULL COMMENT '模型自报耗时（毫秒，仅MODEL段）',
  `provider` varchar(64) DEFAULT NULL COMMENT '实际模型供应商',
  `deployment_id` bigint DEFAULT NULL COMMENT '实际模型部署ID',
  `model_name` varchar(191) DEFAULT NULL COMMENT '实际模型名',
  `price_id` bigint DEFAULT NULL COMMENT '调用时冻结的价目ID',
  `currency` varchar(16) DEFAULT NULL COMMENT '调用时冻结的币种',
  `input_unit_price` decimal(20,8) DEFAULT NULL COMMENT '调用时输入单价（每百万token）',
  `output_unit_price` decimal(20,8) DEFAULT NULL COMMENT '调用时输出单价（每百万token）',
  `cached_unit_price` decimal(20,8) DEFAULT NULL COMMENT '调用时缓存输入单价（每百万token）',
  `pricing_status` varchar(16) NOT NULL DEFAULT 'UNPRICED' COMMENT 'PRICED/UNPRICED',
  `cost_amount` decimal(30,14) DEFAULT NULL COMMENT '按冻结价目结算的模型金额',
  `cost_currency` varchar(16) DEFAULT NULL COMMENT '结算币种',
  `cost_status` varchar(24) NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT 'SETTLED/UNPRICED/USAGE_MISSING/USAGE_INVALID/NOT_APPLICABLE',
  `success` tinyint(1) NOT NULL DEFAULT '1' COMMENT '分段是否成功',
  `error_msg` varchar(1024) DEFAULT NULL COMMENT '失败原因',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_segment_call_log` (`call_log_id`,`seq`),
  KEY `idx_agent_call_segment_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体调用分段明细';

-- ----------------------------------------------------------------------------
-- cw_approval
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_approval` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '审批单号',
  `type` varchar(32) NOT NULL COMMENT '审批类型：REFUND 等',
  `session_id` varchar(128) DEFAULT NULL COMMENT '关联会话',
  `order_id` varchar(64) DEFAULT NULL COMMENT '关联订单号',
  `amount` varchar(32) DEFAULT NULL COMMENT '涉及金额',
  `reason` text COMMENT '诉求原因',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/APPROVED/DENIED',
  `operator` varchar(64) DEFAULT NULL COMMENT '决策操作员',
  `decision_note` text COMMENT '决策备注',
  `decided_at_ms` bigint DEFAULT '0' COMMENT '决策时间戳（毫秒）',
  `execution_status` varchar(24) DEFAULT 'NOT_APPLICABLE' COMMENT '下游执行状态',
  `execution_failure_reason` text COMMENT '下游执行失败原因',
  `execution_attempts` int DEFAULT '0' COMMENT '下游执行尝试次数',
  PRIMARY KEY (`id`),
  KEY `idx_approval_status` (`status`),
  KEY `idx_approval_created` (`created_at_ms`),
  KEY `idx_approval_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工审批工单（退款放行等资金动作）';

-- ----------------------------------------------------------------------------
-- cw_audit_log
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_audit_log` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_type` varchar(64) NOT NULL COMMENT '事件类型: tool-call / final-answer / error',
  `agent_name` varchar(128) DEFAULT '' COMMENT 'Agent 名称',
  `event_data` text COMMENT '结构化事件字段 JSON',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_type` (`event_type`),
  KEY `idx_audit_created` (`created_at`),
  KEY `idx_audit_agent` (`agent_name`),
  KEY `idx_audit_log_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合规审计轨迹（结构化存储）';

-- ----------------------------------------------------------------------------
-- cw_badcase
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_badcase` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT 'badcase ID（应用赋值的UUID）',
  `source` varchar(24) NOT NULL COMMENT '来源：NEGATIVE_FEEDBACK 用户点踩 / QUALITY_FAILURE 质检不过',
  `session_id` varchar(128) DEFAULT NULL COMMENT '所属会话',
  `message_id` varchar(64) DEFAULT NULL COMMENT '被反馈的消息ID（质检来源为空，质检针对一批回复）',
  `user_input` text COMMENT '用户问了什么（从聊天留痕回查）',
  `agent_reply` text COMMENT 'AI答了什么（从聊天留痕回查）',
  `signal_hash` char(64) DEFAULT NULL COMMENT '归一化用户问题SHA-256，供上线复发观测',
  `detail` text COMMENT '原始信号明细：点踩存用户留言，质检存得分与扣分项',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待筛/RESOLVED 已处理/IGNORED 已忽略',
  `adopted_knowledge_id` bigint DEFAULT NULL COMMENT '已回流成的知识条目ID（cw_knowledge.id）',
  `adopted_eval_case_id` varchar(64) DEFAULT NULL COMMENT '已回流成的评测用例编号（cw_eval_case.case_id）',
  `handled_by` varchar(64) DEFAULT NULL COMMENT '处理人',
  `handled_at_ms` bigint DEFAULT NULL COMMENT '处理时间戳（毫秒）',
  `ignore_reason` varchar(500) DEFAULT NULL COMMENT '忽略原因（仅 IGNORED 时有值）',
  `created_at_ms` bigint NOT NULL COMMENT '登记时间戳（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_badcase_status` (`tenant_id`,`status`,`created_at_ms`),
  KEY `idx_badcase_session` (`session_id`),
  KEY `idx_badcase_signal` (`tenant_id`,`signal_hash`,`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='badcase 待筛队列（回流知识库/评测用例）';

-- ----------------------------------------------------------------------------
-- cw_chat_attachment
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_chat_attachment` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '附件ID(UUID)',
  `session_id` varchar(128) NOT NULL DEFAULT '' COMMENT '会话ID',
  `message_id` varchar(64) NOT NULL DEFAULT '' COMMENT '绑定的用户消息ID（框架Msg.id，空=未绑定）',
  `uploader` varchar(128) NOT NULL DEFAULT '' COMMENT '上传者标识',
  `channel` varchar(32) NOT NULL DEFAULT '' COMMENT '来源渠道:user_chat/admin_chat/vibecoding',
  `file_name` varchar(255) NOT NULL COMMENT '原始文件名',
  `extension` varchar(16) NOT NULL DEFAULT '' COMMENT '扩展名',
  `mime_type` varchar(128) NOT NULL DEFAULT '' COMMENT 'MIME类型',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `storage_path` varchar(512) NOT NULL DEFAULT '' COMMENT '落盘相对路径',
  `parse_status` varchar(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态:SUCCESS/FAILED',
  `parsed_text` mediumtext COMMENT '解析出的文本',
  `error_message` varchar(512) DEFAULT NULL COMMENT '解析失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_cw_attachment_session` (`session_id`),
  KEY `idx_cw_attachment_created` (`created_at`),
  KEY `idx_chat_attachment_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话附件';

-- ----------------------------------------------------------------------------
-- cw_chat_message
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_chat_message` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键（游标翻页）',
  `message_id` varchar(64) NOT NULL COMMENT '业务消息号 MSG-<uuid>',
  `session_id` varchar(128) NOT NULL COMMENT '所属会话',
  `ticket_id` varchar(64) DEFAULT NULL COMMENT '关联工单号（可空）',
  `sender_type` varchar(16) NOT NULL COMMENT '发送方类型 USER/BOT/AGENT/SYSTEM',
  `sender_id` varchar(64) DEFAULT NULL COMMENT '发送方标识（可空）',
  `content` text NOT NULL COMMENT '消息内容',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_message_id` (`message_id`),
  KEY `idx_chat_session` (`session_id`,`id`),
  KEY `idx_chat_ticket` (`ticket_id`,`id`),
  KEY `idx_chat_message_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话/工单聊天消息留痕';

-- ----------------------------------------------------------------------------
-- cw_complaint
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_complaint` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `complaint_no` varchar(64) NOT NULL COMMENT '投诉工单号',
  `order_id` varchar(32) DEFAULT NULL COMMENT '关联订单号（可空）',
  `content` text COMMENT '投诉内容',
  `status` varchar(16) NOT NULL COMMENT '状态：PROCESSING/RESOLVED',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  PRIMARY KEY (`complaint_no`),
  KEY `idx_complaint_order` (`order_id`,`created_at_ms`),
  KEY `idx_complaint_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='投诉工单（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_csat_survey
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_csat_survey` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `session_id` varchar(128) NOT NULL COMMENT '会话ID（自然主键：一次会话只该有一次整体评价）',
  `scope_id` varchar(128) NOT NULL DEFAULT 'default' COMMENT '运营统计分区键 = 租户码（OpsScopeResolver 取当前租户上下文）',
  `score` tinyint DEFAULT NULL COMMENT '评分 1-5；NULL 表示已邀请未评价（回收率的分母靠它区分）',
  `comment` text COMMENT '文字说明',
  `invited_at_ms` bigint NOT NULL COMMENT '发出邀请时间戳（毫秒）——统计窗口以它为准',
  `submitted_at_ms` bigint NOT NULL DEFAULT '0' COMMENT '提交评分时间戳（毫秒）；未评价为 0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`session_id`),
  KEY `idx_csat_window` (`tenant_id`,`scope_id`,`invited_at_ms`),
  KEY `idx_csat_score` (`tenant_id`,`score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话级满意度调查（CSAT）';

-- ----------------------------------------------------------------------------
-- cw_dead_letter
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_dead_letter` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '死信ID（应用赋值的UUID）',
  `type` varchar(64) NOT NULL COMMENT '死信类型：决定由哪个 DeadLetterHandler 重投',
  `payload` text NOT NULL COMMENT '重投所需的完整载荷（JSON）',
  `biz_key` varchar(128) DEFAULT NULL COMMENT '关联业务标识（订单号/会话号），供运营检索',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待重投/SUCCEEDED 已成功/ABANDONED 已放弃',
  `attempts` int NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `last_error` text COMMENT '最近一次失败原因',
  `next_retry_at_ms` bigint NOT NULL COMMENT '下次重投时刻（毫秒）',
  `lease_owner` varchar(128) DEFAULT NULL COMMENT '当前租约持有实例',
  `lease_until_ms` bigint NOT NULL DEFAULT '0' COMMENT '租约到期时间',
  `created_at_ms` bigint NOT NULL COMMENT '失败发生时刻（毫秒）',
  `finished_at_ms` bigint NOT NULL DEFAULT '0' COMMENT '终态时刻（成功或放弃）；未终结为 0',
  PRIMARY KEY (`id`),
  KEY `idx_dead_letter_due` (`tenant_id`,`status`,`next_retry_at_ms`),
  KEY `idx_dead_letter_biz` (`tenant_id`,`biz_key`),
  KEY `idx_dead_letter_lease` (`tenant_id`,`status`,`lease_until_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='死信队列（失败操作的兜底重投）';

-- ----------------------------------------------------------------------------
-- cw_dialog_stage
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_dialog_stage` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `session_id` varchar(191) NOT NULL COMMENT '会话 ID',
  `stage` varchar(24) NOT NULL COMMENT '当前对话阶段：GREETING/COLLECTING/PROCESSING/CONFIRMING/ESCALATED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`session_id`),
  KEY `idx_dialog_stage_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话阶段状态机（多实例共享）';

-- ----------------------------------------------------------------------------
-- cw_dict_item
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_dict_item` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `dict_type` varchar(64) NOT NULL COMMENT '所属字典类型编码',
  `item_key` varchar(128) NOT NULL COMMENT '字典项键（业务值）',
  `item_label` varchar(128) NOT NULL COMMENT '字典项标签（展示文案）',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序号，越小越靠前',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用/0停用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注说明',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_item` (`tenant_id`,`dict_type`,`item_key`),
  KEY `idx_dict_item_type` (`dict_type`),
  KEY `idx_dict_item_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典项表';

-- ----------------------------------------------------------------------------
-- cw_dict_type
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_dict_type` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `dict_type` varchar(64) NOT NULL COMMENT '字典类型编码（如 order_status）',
  `type_name` varchar(64) NOT NULL COMMENT '类型名称（展示用）',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注说明',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用/0停用',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_type` (`tenant_id`,`dict_type`),
  KEY `idx_dict_type_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典类型表';

-- ----------------------------------------------------------------------------
-- cw_eval_case
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_eval_case` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `eval_type` varchar(16) NOT NULL COMMENT '评测类型：INTENT 意图路由 / QUALITY 回复质量',
  `case_id` varchar(64) NOT NULL COMMENT '用例编号（同类型内唯一；与种子同号即覆盖种子）',
  `input` varchar(1024) NOT NULL COMMENT '用户输入',
  `expected` varchar(1024) DEFAULT NULL COMMENT 'INTENT=期望意图（空=期望快车道不命中，交LLM）；QUALITY=期望要点',
  `category` varchar(64) DEFAULT NULL COMMENT '归类标签',
  `source` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：SEED 种子/BADCASE 回流/MANUAL 手工',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否参与评测：0 可屏蔽同号种子用例',
  `origin_ref` varchar(64) DEFAULT NULL COMMENT '溯源引用：来自 badcase 时记 badcase ID，便于回看原始会话',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_eval_case` (`tenant_id`,`eval_type`,`case_id`),
  KEY `idx_eval_case_tenant` (`tenant_id`,`eval_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测用例（种子之外的增量与修正）';

-- ----------------------------------------------------------------------------
-- cw_eval_dataset_release
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_eval_dataset_release` (
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `release_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '命名版本ID（应用生成UUID）',
  `eval_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'INTENT/QUALITY',
  `version_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户内、类型内唯一的人类可读版本名',
  `snapshot_version_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '不可变内容快照 cw_eval_dataset_version.version_id',
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '快照内容SHA-256，跨库绑定时用于校验漂移',
  `case_count` int NOT NULL COMMENT '版本包含的用例数',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/APPROVED/REJECTED',
  `review_comment` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核意见',
  `created_by` bigint DEFAULT NULL COMMENT '创建人',
  `reviewed_by` bigint DEFAULT NULL COMMENT '审核人',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  `reviewed_at_ms` bigint DEFAULT NULL COMMENT '审核时间戳（毫秒）',
  PRIMARY KEY (`release_id`),
  UNIQUE KEY `uk_eval_dataset_release_name` (`tenant_id`,`eval_type`,`version_name`),
  KEY `idx_eval_dataset_release_status` (`tenant_id`,`eval_type`,`status`,`created_at_ms`),
  KEY `idx_eval_dataset_release_snapshot` (`tenant_id`,`snapshot_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测数据集命名版本与审核';

-- ----------------------------------------------------------------------------
-- cw_eval_dataset_version
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_eval_dataset_version` (
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `version_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据集版本ID（应用生成UUID）',
  `eval_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'INTENT/QUALITY',
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规范化用例JSON的SHA-256',
  `case_count` int NOT NULL COMMENT '快照用例数',
  `cases_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '本次实际执行的完整用例JSON',
  `created_at_ms` bigint NOT NULL COMMENT '首次创建时间戳（毫秒）',
  PRIMARY KEY (`version_id`),
  UNIQUE KEY `uk_eval_dataset_content` (`tenant_id`,`eval_type`,`content_hash`),
  KEY `idx_eval_dataset_tenant_time` (`tenant_id`,`eval_type`,`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测数据集不可变版本';

-- ----------------------------------------------------------------------------
-- cw_eval_run
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_eval_run` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `run_id` varchar(64) NOT NULL COMMENT '运行ID（应用赋值的UUID）',
  `seq` bigint NOT NULL AUTO_INCREMENT COMMENT '写入顺序号（同毫秒也不丢序）',
  `eval_type` varchar(16) NOT NULL COMMENT '评测类型：INTENT 意图路由 / QUALITY 回复质量',
  `total` int NOT NULL DEFAULT '0' COMMENT '用例总数',
  `passed` int NOT NULL DEFAULT '0' COMMENT '通过数',
  `primary_metric` double NOT NULL DEFAULT '0' COMMENT '主指标(0-1)：INTENT=准确率 / QUALITY=平均分/5',
  `secondary_metric` double NOT NULL DEFAULT '0' COMMENT '次指标(0-1)：INTENT=快车道覆盖率 / QUALITY=通过率',
  `failed_case_ids_json` text COMMENT '失败用例ID的JSON数组（版本间回归识别的依据，不从明细里反解）',
  `failures_json` text COMMENT '失败明细的JSON数组（人读）',
  `metrics_json` text COMMENT '该类型完整原始指标的JSON字典（归一化不丢信息）',
  `trigger_source` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT '触发来源：MANUAL/SCHEDULED/API',
  `dataset_size` int NOT NULL DEFAULT '0' COMMENT '评测集规模（用例增删后两次指标不可直接比）',
  `dataset_version_id` varchar(64) DEFAULT NULL COMMENT '本次实际执行的数据集版本',
  `dataset_fingerprint` varchar(64) DEFAULT NULL COMMENT '数据集内容SHA-256',
  `version_binding_json` text COMMENT '模型/提示词/Agent/知识/工具/Judge/rubric版本绑定JSON',
  `prompt_fingerprint` varchar(32) DEFAULT NULL COMMENT '本次运行时生效的提示词指纹（cw_prompt_version.fingerprint）',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注（如"换 qwen-max 后重跑"）',
  `created_at_ms` bigint NOT NULL COMMENT '运行时间戳（毫秒）',
  PRIMARY KEY (`run_id`),
  UNIQUE KEY `seq` (`seq`),
  KEY `idx_eval_run_type_time` (`tenant_id`,`eval_type`,`created_at_ms`),
  KEY `idx_eval_run_dataset_version` (`tenant_id`,`eval_type`,`dataset_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测运行记录（只追加，供版本对比）';

-- ----------------------------------------------------------------------------
-- cw_fact_log
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_fact_log` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键（即写入顺序）',
  `scope_id` varchar(128) NOT NULL DEFAULT 'default' COMMENT '记忆分区键（TenantResolver 由 sessionId 解析）',
  `fact` text NOT NULL COMMENT '事实内容',
  `ts` bigint NOT NULL COMMENT '事实时间戳（毫秒）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_fact_log_scope` (`tenant_id`,`scope_id`,`id`),
  KEY `idx_fact_log_ts` (`tenant_id`,`scope_id`,`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实日志（L3，append-only 审计流水，永不改写）';

-- ----------------------------------------------------------------------------
-- cw_handoff_ticket
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_handoff_ticket` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '工单号',
  `session_id` varchar(128) DEFAULT NULL COMMENT '关联会话',
  `reason` text COMMENT '转人工原因',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/CLAIMED/RESOLVED',
  `claimed_by` varchar(64) DEFAULT NULL COMMENT '接单坐席',
  `claimed_at_ms` bigint DEFAULT '0' COMMENT '接单时间戳（毫秒）',
  `resolution_note` text COMMENT '处理结果备注',
  `resolved_at_ms` bigint DEFAULT '0' COMMENT '结案时间戳（毫秒）',
  `category` varchar(64) DEFAULT NULL COMMENT '工单分类（LLM 分类，可空）',
  `required_skill` varchar(64) DEFAULT NULL COMMENT '所需坐席技能标签（LLM 分类，可空）',
  `priority` varchar(16) DEFAULT NULL COMMENT '优先级 LOW/MEDIUM/HIGH/URGENT（LLM 分类，可空）',
  `emotion` varchar(32) DEFAULT NULL COMMENT '用户情绪（LLM 分类，可空）',
  `suggested_assignees` text COMMENT '推荐坐席列表 JSON（HITL 推荐，人工点选非自动派单，可空）',
  PRIMARY KEY (`id`),
  KEY `idx_handoff_status` (`status`),
  KEY `idx_handoff_created` (`created_at_ms`),
  KEY `idx_handoff_ticket_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人机切换工单（AI 转人工闭环 + 智能分配增强）';

-- ----------------------------------------------------------------------------
-- cw_harness_memory
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_harness_memory` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `scope_id` varchar(512) NOT NULL COMMENT '记忆归属（workspace 目录路径）',
  `scope_hash` varchar(64) NOT NULL COMMENT 'scope_id 的 SHA-256（唯一键用，规避 512 字节索引长度限制）',
  `content` mediumtext NOT NULL COMMENT 'MEMORY.md 全文',
  `updated_at_ms` bigint NOT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_harness_memory_scope` (`tenant_id`,`scope_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Harness 分层记忆（MEMORY.md 权威副本）';

-- ----------------------------------------------------------------------------
-- cw_invoice_request
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_invoice_request` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '发票申请自增主键',
  `order_id` varchar(32) NOT NULL COMMENT '关联订单号',
  `invoice_title` varchar(255) NOT NULL COMMENT '发票抬头',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/ISSUED',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_invoice_order` (`order_id`,`created_at_ms`),
  KEY `idx_invoice_request_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发票申请（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_knowledge
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_knowledge` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '知识条目自增主键',
  `keyword` varchar(255) NOT NULL COMMENT '命中关键词（逗号分隔）',
  `title` varchar(255) NOT NULL COMMENT '条目标题',
  `content` text NOT NULL COMMENT '条目内容',
  `source` varchar(255) DEFAULT NULL COMMENT '来源标注',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_title` (`tenant_id`,`title`),
  KEY `idx_knowledge_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库 FAQ（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_knowledge_gap
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_knowledge_gap` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `question_hash` varchar(64) NOT NULL COMMENT '问题原文的 SHA-256',
  `question` varchar(512) NOT NULL COMMENT '问题原文（截断保存）——运营要看的就是这个',
  `scope_id` varchar(128) NOT NULL DEFAULT 'default' COMMENT '运营统计分区键 = 租户码（OpsScopeResolver 取当前租户上下文）',
  `miss_count` bigint NOT NULL DEFAULT '1' COMMENT '累计未命中次数：排行依据，越大越该优先补',
  `first_seen_at_ms` bigint NOT NULL COMMENT '首次出现时间戳（毫秒）——这个问题何时开始查不到',
  `last_seen_at_ms` bigint NOT NULL COMMENT '最近出现时间戳（毫秒）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_gap` (`tenant_id`,`scope_id`,`question_hash`),
  KEY `idx_knowledge_gap_rank` (`tenant_id`,`scope_id`,`miss_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识盲区（反复检索不到的问题，计数表）';

-- ----------------------------------------------------------------------------
-- cw_long_term_memory
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_long_term_memory` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `scope_id` varchar(128) NOT NULL DEFAULT 'default' COMMENT '记忆分区键（TenantResolver 由 sessionId 解析）',
  `fact` text NOT NULL COMMENT '事实内容',
  `scope_hash` varchar(64) NOT NULL COMMENT 'scope_id + fact 的 SHA-256（去重键，TEXT 无法建唯一索引）',
  `created_at_ms` bigint NOT NULL COMMENT '写入时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ltm_scope_fact` (`tenant_id`,`scope_hash`),
  KEY `idx_ltm_scope` (`tenant_id`,`scope_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆事实（L2，跨会话语义召回）';

-- ----------------------------------------------------------------------------
-- cw_member
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_member` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `member_id` varchar(64) NOT NULL COMMENT '会员ID（对应用户ID）',
  `level` varchar(32) NOT NULL COMMENT '会员等级',
  `points` int NOT NULL DEFAULT '0' COMMENT '当前积分',
  `points_expiring` int NOT NULL DEFAULT '0' COMMENT '本月底到期积分',
  `benefits` varchar(255) DEFAULT NULL COMMENT '等级权益',
  `next_level` varchar(32) DEFAULT NULL COMMENT '下一等级',
  `upgrade_gap` decimal(10,2) DEFAULT '0.00' COMMENT '升级所需再消费金额',
  `phone` varchar(32) DEFAULT NULL COMMENT '注册手机号',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`member_id`),
  KEY `idx_member_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_member_account_log
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_member_account_log` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '处理日志自增主键',
  `issue` varchar(255) NOT NULL COMMENT '账户问题描述',
  `handling` varchar(500) DEFAULT NULL COMMENT '处置话术',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_account_log_created` (`created_at_ms`),
  KEY `idx_member_account_log_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员账户问题处理日志（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_memory_consent
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_memory_consent` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `subject_type` varchar(32) NOT NULL COMMENT '主体类型: USER/SESSION/SERVICE_ACCOUNT',
  `subject_id` varchar(128) NOT NULL COMMENT '租户内主体ID',
  `agent_id` varchar(128) NOT NULL COMMENT 'Agent稳定标识',
  `scope_id` varchar(68) NOT NULL COMMENT '四维主体键SHA-256分区',
  `status` varchar(16) NOT NULL COMMENT 'GRANTED/WITHDRAWN',
  `consent_version` varchar(64) NOT NULL COMMENT '用户同意的隐私条款版本',
  `granted_at_ms` bigint DEFAULT NULL COMMENT '授权时间戳（毫秒）',
  `withdrawn_at_ms` bigint DEFAULT NULL COMMENT '撤回时间戳（毫秒）',
  `updated_at_ms` bigint NOT NULL COMMENT '最后更新时间戳（毫秒）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_consent_subject` (`tenant_id`,`subject_type`,`subject_id`,`agent_id`),
  UNIQUE KEY `uk_memory_consent_scope` (`tenant_id`,`scope_id`),
  KEY `idx_memory_consent_status` (`tenant_id`,`status`,`updated_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆主体同意记录';

-- ----------------------------------------------------------------------------
-- cw_message_feedback
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_message_feedback` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `message_id` varchar(64) NOT NULL COMMENT '被反馈的消息ID',
  `session_id` varchar(128) DEFAULT NULL COMMENT '所属会话',
  `type` varchar(8) NOT NULL COMMENT 'UP/DOWN',
  `comment` text COMMENT '文字说明',
  `created_at_ms` bigint NOT NULL COMMENT '提交时间戳（毫秒，重复提交取最新）',
  PRIMARY KEY (`message_id`),
  KEY `idx_feedback_session` (`session_id`),
  KEY `idx_feedback_type` (`type`),
  KEY `idx_message_feedback_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息级用户反馈（点赞/点踩）';

-- ----------------------------------------------------------------------------
-- cw_order
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_order` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `order_id` varchar(32) NOT NULL COMMENT '订单号',
  `user_id` varchar(64) NOT NULL COMMENT '下单用户',
  `product_id` varchar(32) NOT NULL COMMENT '商品ID',
  `product_name` varchar(128) DEFAULT NULL COMMENT '商品名称',
  `amount` decimal(10,2) NOT NULL COMMENT '订单金额',
  `status` varchar(32) NOT NULL COMMENT '订单状态',
  `receiver_addr` varchar(255) DEFAULT NULL COMMENT '收货地址',
  `logistics_trace` text COMMENT '物流轨迹',
  `created_at_ms` bigint NOT NULL COMMENT '下单时间戳（毫秒）',
  PRIMARY KEY (`order_id`),
  KEY `idx_order_user` (`user_id`,`created_at_ms`),
  KEY `idx_order_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_outbox_message
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_outbox_message` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '消息ID，也是下游幂等键',
  `type` varchar(64) NOT NULL COMMENT 'Handler 类型',
  `aggregate_id` varchar(128) NOT NULL COMMENT '聚合根业务标识',
  `payload` mediumtext NOT NULL COMMENT '自包含 JSON 载荷',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCEEDED/ABANDONED',
  `attempts` int NOT NULL DEFAULT '0' COMMENT '投递失败次数',
  `next_attempt_at_ms` bigint NOT NULL COMMENT '下次投递时间',
  `lease_owner` varchar(128) DEFAULT NULL COMMENT '当前租约持有实例',
  `lease_until_ms` bigint NOT NULL DEFAULT '0' COMMENT '租约到期时间',
  `last_error` text COMMENT '最近一次投递错误',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间',
  `finished_at_ms` bigint NOT NULL DEFAULT '0' COMMENT '终态时间',
  PRIMARY KEY (`id`),
  KEY `idx_outbox_due` (`tenant_id`,`status`,`next_attempt_at_ms`),
  KEY `idx_outbox_lease` (`tenant_id`,`status`,`lease_until_ms`),
  KEY `idx_outbox_aggregate` (`tenant_id`,`aggregate_id`,`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同库事务 Outbox';

-- ----------------------------------------------------------------------------
-- cw_product
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_product` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `product_id` varchar(32) NOT NULL COMMENT '商品ID',
  `name` varchar(128) NOT NULL COMMENT '商品名称',
  `category` varchar(64) DEFAULT NULL COMMENT '品类',
  `price` decimal(10,2) NOT NULL COMMENT '价格',
  `stock` int NOT NULL DEFAULT '0' COMMENT '库存',
  `description` varchar(500) DEFAULT NULL COMMENT '商品描述',
  `promotion` varchar(255) DEFAULT NULL COMMENT '优惠活动',
  `status` varchar(16) NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE/OFF_SALE',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`product_id`),
  KEY `idx_product_category` (`category`),
  KEY `idx_product_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_prompt_version
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_prompt_version` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `fingerprint` varchar(32) NOT NULL COMMENT '内容指纹（SHA-256 十六进制前16位）',
  `content` mediumtext NOT NULL COMMENT '提示词全文（归因时比对两版差异）',
  `length` int NOT NULL DEFAULT '0' COMMENT '全文字符数（列表页展示，避免每行拖全文）',
  `captured_at_ms` bigint NOT NULL COMMENT '首次观测到该版本的时间戳（毫秒）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`fingerprint`),
  KEY `idx_prompt_version_time` (`tenant_id`,`captured_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提示词版本（运行时实际生效的那份）';

-- ----------------------------------------------------------------------------
-- cw_rate_limit_rule
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_rate_limit_rule` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `rule_name` varchar(64) NOT NULL COMMENT '规则名（运营可读）',
  `path_prefix` varchar(128) NOT NULL COMMENT '匹配的请求路径前缀',
  `dimension` varchar(16) NOT NULL COMMENT '计数维度: API_KEY/IP/GLOBAL',
  `limit_count` int NOT NULL COMMENT '窗口内允许的最大请求数',
  `algorithm` varchar(32) NOT NULL COMMENT '算法: FIXED_WINDOW/SLIDING_WINDOW',
  `window_seconds` int NOT NULL DEFAULT '60' COMMENT '时间窗（秒）',
  `priority` int NOT NULL DEFAULT '0' COMMENT '优先级，越小越先匹配（首匹配即止）',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用/0停用',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rate_limit_rule_name` (`tenant_id`,`rule_name`),
  KEY `idx_rate_limit_priority` (`priority`),
  KEY `idx_rate_limit_rule_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='限流规则表（接入层限流规则层）';

-- ----------------------------------------------------------------------------
-- cw_refund
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_refund` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `refund_no` varchar(64) NOT NULL COMMENT '售后工单号',
  `order_id` varchar(32) NOT NULL COMMENT '关联订单号',
  `type` varchar(16) NOT NULL COMMENT '类型：REFUND/RETURN/EXCHANGE',
  `status` varchar(16) NOT NULL COMMENT '状态：PENDING/APPROVED/DENIED',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '退款金额（退货/换货可空）',
  `reason` varchar(500) DEFAULT NULL COMMENT '诉求原因',
  `new_spec` varchar(128) DEFAULT NULL COMMENT '换货目标规格',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  PRIMARY KEY (`refund_no`),
  KEY `idx_refund_order` (`order_id`,`created_at_ms`),
  KEY `idx_refund_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后工单（JDBC 后端演示表）';

-- ----------------------------------------------------------------------------
-- cw_seat_agent
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_seat_agent` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '坐席ID',
  `name` varchar(64) NOT NULL COMMENT '坐席名',
  `skills` varchar(512) DEFAULT NULL COMMENT '技能标签（逗号分隔，如 refund,invoice）',
  `max_load` int NOT NULL DEFAULT '0' COMMENT '最大并发工单数',
  `current_load` int NOT NULL DEFAULT '0' COMMENT '当前在处理工单数',
  `online` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否在线: 1在线/0离线',
  `seat_group` varchar(64) DEFAULT NULL COMMENT '坐席分组',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_seat_online` (`online`),
  KEY `idx_seat_agent_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='坐席库（智能分配候选坐席池）';

-- ----------------------------------------------------------------------------
-- cw_semantic_cache
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_semantic_cache` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离，拦截器自动改写）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `scope_id` varchar(128) NOT NULL DEFAULT 'default' COMMENT '缓存分区键',
  `config_generation` varchar(64) NOT NULL DEFAULT 'bootstrap' COMMENT '写入时运行配置 contentHash；bootstrap 表示尚未接入热配置',
  `intent` varchar(32) NOT NULL COMMENT '意图分类，命中时先按它缩小候选集（关键剪枝）',
  `question` varchar(512) NOT NULL COMMENT '原始问题（人读，排查"为什么这条命中了"要看）',
  `question_vector` mediumtext NOT NULL COMMENT '问题向量，逗号分隔浮点数',
  `answer` text NOT NULL COMMENT '当时的回答',
  `hit_count` bigint NOT NULL DEFAULT '0' COMMENT '命中次数（容量淘汰时保留高频条目）',
  `created_at_ms` bigint NOT NULL COMMENT '写入时间戳（毫秒），TTL 以此为准',
  `last_hit_at_ms` bigint NOT NULL COMMENT '最近命中时间戳（毫秒），LRU 淘汰以此为准',
  PRIMARY KEY (`id`),
  KEY `idx_semcache_lookup` (`tenant_id`,`config_generation`,`scope_id`,`intent`,`last_hit_at_ms`),
  KEY `idx_semcache_created` (`tenant_id`,`config_generation`,`scope_id`,`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='语义缓存（仅通用问答，默认关闭）';

-- ----------------------------------------------------------------------------
-- cw_sensitive_word
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_sensitive_word` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `word` varchar(128) NOT NULL COMMENT '敏感词原词面',
  `category` varchar(32) NOT NULL COMMENT '类目: POLITICS/PORN/ABUSE/COMPETITOR/CUSTOM',
  `action` varchar(16) NOT NULL COMMENT '处置动作: BLOCK/MASK/REVIEW',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用/0停用',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sensitive_word` (`tenant_id`,`word`),
  KEY `idx_sensitive_word_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词表（一次拦截词库）';

-- ----------------------------------------------------------------------------
-- cw_sensitive_word_hit_log
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_sensitive_word_hit_log` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `direction` varchar(16) NOT NULL COMMENT '命中方向: INBOUND用户输入/OUTBOUND模型输出',
  `action` varchar(16) NOT NULL COMMENT '整体决策: BLOCK/MASK/REVIEW',
  `words` varchar(512) DEFAULT NULL COMMENT '命中词面，逗号分隔',
  `categories` varchar(128) DEFAULT NULL COMMENT '命中类目，逗号分隔已去重',
  `hit_count` int NOT NULL DEFAULT '0' COMMENT '命中词个数',
  `agent_name` varchar(128) DEFAULT NULL COMMENT '智能体名',
  `session_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `user_id` varchar(128) DEFAULT NULL COMMENT '用户ID',
  `snippet` varchar(512) DEFAULT NULL COMMENT '原文片段（已按配置截断）',
  `created_at_ms` bigint DEFAULT NULL COMMENT '命中时间戳（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_hit_created` (`created_at_ms`),
  KEY `idx_hit_action` (`action`),
  KEY `idx_hit_session` (`session_id`),
  KEY `idx_sensitive_word_hit_log_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词命中日志（后台看板数据源）';

-- ----------------------------------------------------------------------------
-- cw_skill
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_skill` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `skill_code` varchar(64) NOT NULL COMMENT '技能编码（= 落盘目录名）',
  `skill_name` varchar(64) NOT NULL COMMENT '技能名称',
  `content` mediumtext NOT NULL COMMENT 'SKILL.md 正文',
  `description` varchar(255) DEFAULT NULL COMMENT '技能描述',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用/0停用',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cw_skill_code` (`tenant_id`,`skill_code`),
  KEY `idx_cw_skill_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技能库（SKILL.md 正文）';

-- ----------------------------------------------------------------------------
-- cw_skill_file
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_skill_file` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `skill_id` bigint NOT NULL COMMENT '所属技能（cw_skill.id）',
  `file_path` varchar(512) NOT NULL COMMENT '相对 SKILL.md 所在目录的路径，如 references/api.md',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `content` longblob COMMENT '文件内容（文本/二进制统一按字节存）',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_cw_skill_file_skill` (`skill_id`),
  KEY `idx_cw_skill_file_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技能附属文件';

-- ----------------------------------------------------------------------------
-- cw_slot_filling_progress
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_slot_filling_progress` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `progress_key` varchar(191) NOT NULL COMMENT '收集进度键：sessionId:formName',
  `asking` varchar(64) DEFAULT NULL COMMENT '当前追问的槽位名',
  `collected_json` text COMMENT '已收集槽位值（JSON）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '记录最后修改时间',
  PRIMARY KEY (`progress_key`),
  KEY `idx_slot_filling_progress_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多轮槽位收集进度（如退款表单信息采集）';

-- ----------------------------------------------------------------------------
-- cw_subject_quota_hit
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_subject_quota_hit` (
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `subject_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型: USER/IP/API_KEY',
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体标识（API Key 已做 SHA-256 指纹，不含明文）',
  `level_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '判定所依据的等级',
  `limit_kind` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触顶维度: TOKEN/REQUEST',
  `used` bigint NOT NULL DEFAULT '0' COMMENT '触顶时已用量',
  `limit_value` bigint NOT NULL DEFAULT '0' COMMENT '触顶时的上限',
  `window_seconds` int NOT NULL DEFAULT '0' COMMENT '滚动窗口长度（秒）',
  `action` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'BLOCK' COMMENT '当时处置: BLOCK 真拦了 / WARN 只记录',
  `resource` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '触发位置（HTTP 路径或 ws:chat）',
  `created_at_ms` bigint NOT NULL COMMENT '命中时刻（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_squota_hit_tenant_time` (`tenant_id`,`created_at_ms`),
  KEY `idx_squota_hit_subject` (`tenant_id`,`subject_type`,`subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主体配额超限命中记录';

-- ----------------------------------------------------------------------------
-- cw_subject_quota_level
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_subject_quota_level` (
  `tenant_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `level_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '等级编码，如 free/vip/anonymous',
  `level_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '等级名称（运营可读）',
  `subject_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER' COMMENT '适用主体: USER 登录用户 / IP 匿名 / API_KEY 接入方',
  `window_seconds` int NOT NULL DEFAULT '1800' COMMENT '滚动窗口长度（秒），1800=30分钟',
  `token_limit` bigint NOT NULL DEFAULT '0' COMMENT '窗口内 token 上限，0=不限',
  `request_limit` int NOT NULL DEFAULT '0' COMMENT '窗口内请求次数上限，0=不限',
  `exceed_action` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'BLOCK' COMMENT '超限处置: BLOCK 拦截 / WARN 仅记录',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用/0停用',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_squota_level` (`tenant_id`,`level_code`),
  KEY `idx_squota_level_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主体配额等级（每租户每档一条）';

-- ----------------------------------------------------------------------------
-- cw_tenant_quota
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_tenant_quota` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `period` varchar(16) NOT NULL COMMENT '周期: DAILY 日 / MONTHLY 月',
  `token_limit` bigint NOT NULL DEFAULT '0' COMMENT 'token 上限，0=不限',
  `amount_limit` decimal(16,4) NOT NULL DEFAULT '0.0000' COMMENT '金额上限（元），0=不限；实时链路只拦 token，金额走 T+1 账单告警',
  `exceed_action` varchar(16) NOT NULL DEFAULT 'BLOCK' COMMENT '超额处置: BLOCK 拦截 / DEGRADE 降级备用模型 / WARN 仅告警',
  `warn_percent` int NOT NULL DEFAULT '80' COMMENT '预警阈值（用量百分比），达到即告警但不拦',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用: 1启用/0停用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `created_at_ms` bigint DEFAULT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint DEFAULT NULL COMMENT '更新时间戳（毫秒）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_quota` (`tenant_id`,`period`),
  KEY `idx_tenant_quota_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户配额（每租户每周期一条）';

-- ----------------------------------------------------------------------------
-- cw_ticket
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_ticket` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '工单号',
  `session_id` varchar(128) NOT NULL COMMENT '关联会话',
  `user_id` varchar(64) NOT NULL COMMENT '发起用户',
  `title` varchar(255) DEFAULT NULL COMMENT '工单标题',
  `category` varchar(32) NOT NULL DEFAULT 'OTHER' COMMENT '分类',
  `priority` varchar(16) NOT NULL DEFAULT 'NORMAL' COMMENT '优先级',
  `status` varchar(32) NOT NULL COMMENT '状态机状态',
  `assignee` varchar(64) DEFAULT NULL COMMENT '当前处理坐席',
  `handoff_reason` varchar(255) DEFAULT NULL COMMENT '转人工原因',
  `resolve_note` text COMMENT '处理结论/备注',
  `routing_category` varchar(64) DEFAULT NULL COMMENT '智能路由分类原文',
  `required_skill` varchar(64) DEFAULT NULL COMMENT '所需坐席技能',
  `routing_priority` varchar(16) DEFAULT NULL COMMENT '智能路由优先级原文',
  `emotion` varchar(32) DEFAULT NULL COMMENT '用户情绪',
  `suggested_assignees` text COMMENT '推荐坐席列表 JSON',
  `reopen_count` int NOT NULL DEFAULT '0' COMMENT '重开次数',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  `updated_at_ms` bigint NOT NULL COMMENT '更新时间戳（毫秒）',
  `handoff_at_ms` bigint DEFAULT '0' COMMENT '最近转人工时间戳（毫秒）',
  `claimed_at_ms` bigint DEFAULT '0' COMMENT '接单时间戳（毫秒）',
  `resolved_at_ms` bigint DEFAULT '0' COMMENT '解决时间戳（毫秒）',
  `closed_at_ms` bigint DEFAULT '0' COMMENT '关闭时间戳（毫秒）',
  `last_user_active_at_ms` bigint DEFAULT '0' COMMENT '用户最后活跃时间戳（毫秒，空闲超时巡检基准）',
  PRIMARY KEY (`id`),
  KEY `idx_ticket_session` (`session_id`),
  KEY `idx_ticket_user` (`user_id`,`created_at_ms`),
  KEY `idx_ticket_status` (`status`,`updated_at_ms`),
  KEY `idx_ticket_assignee` (`assignee`,`status`),
  KEY `idx_ticket_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客服工单（完整生命周期状态机）';

-- ----------------------------------------------------------------------------
-- cw_ticket_event
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_ticket_event` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件自增主键',
  `ticket_id` varchar(64) NOT NULL COMMENT '所属工单号',
  `event_type` varchar(32) NOT NULL COMMENT '事件类型',
  `from_status` varchar(32) DEFAULT NULL COMMENT '流转前状态',
  `to_status` varchar(32) DEFAULT NULL COMMENT '流转后状态',
  `actor_type` varchar(16) NOT NULL COMMENT '动作发起方类型',
  `actor_id` varchar(64) DEFAULT NULL COMMENT '动作发起方标识',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `created_at_ms` bigint NOT NULL COMMENT '事件时间戳（毫秒）',
  PRIMARY KEY (`id`),
  KEY `idx_ticket_event` (`ticket_id`,`id`),
  KEY `idx_ticket_event_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单事件轨迹（不可变审计）';

-- ----------------------------------------------------------------------------
-- cw_user
-- ----------------------------------------------------------------------------
CREATE TABLE `cw_user` (
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
  `id` varchar(64) NOT NULL COMMENT '用户ID',
  `username` varchar(64) NOT NULL COMMENT '用户名',
  `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt 密码哈希',
  `nickname` varchar(64) DEFAULT NULL COMMENT '昵称',
  `phone` varchar(32) DEFAULT NULL COMMENT '手机号',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_at_ms` bigint NOT NULL COMMENT '创建时间戳（毫秒）',
  `avatar_url` varchar(255) DEFAULT NULL COMMENT '头像访问URL（相对路径，可为空）',
  `level_code` varchar(64) DEFAULT NULL COMMENT '配额等级编码（空=默认档）',
  `session_epoch` bigint NOT NULL DEFAULT '0' COMMENT '用户会话撤销版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`tenant_id`,`username`),
  KEY `idx_user_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客服系统终端用户账户';
