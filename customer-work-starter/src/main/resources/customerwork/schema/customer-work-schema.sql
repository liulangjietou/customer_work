-- =============================================================================
-- customer-work 业务表结构 + 种子（SchemaInitializer 启动时按 session.mysql.auto-create 执行）
-- =============================================================================
-- 说明：
--   1. 覆盖十个持久化域（审计/审批/槽位/对话阶段/人机切换/反馈/工单/用户/聊天日志/对话附件）
--      与六个工具后端演示表（订单/商品/售后+发票/会员+账户日志/投诉/知识库）。
--   2. 内容与 mysql/01-agent-scope-customer-work/customer-work-schema.sql（DBA 手册）的业务表 DDL 一致；此处不含 CREATE DATABASE / USE
--      与框架会话表 agentscope_sessions（库由 JDBC URL 的 createDatabaseIfNotExist 建，会话表由框架建）。
--   3. 全部 CREATE TABLE IF NOT EXISTS + INSERT IGNORE，可重复执行（幂等）。
-- =============================================================================

-- 合规审计日志表（MybatisAuditSink）。
CREATE TABLE IF NOT EXISTS `cw_audit_log` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `event_type`  VARCHAR(64) NOT NULL COMMENT '事件类型: tool-call / final-answer / error',
    `agent_name`  VARCHAR(128) DEFAULT '' COMMENT 'Agent 名称',
    `event_data`  TEXT COMMENT '结构化事件字段 JSON',
    `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    INDEX `idx_audit_type` (`event_type`),
    INDEX `idx_audit_created` (`created_at`),
    INDEX `idx_audit_agent` (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合规审计轨迹（结构化存储）';

-- 人工审批工单表（MybatisApprovalStore）。
CREATE TABLE IF NOT EXISTS `cw_approval` (
    `id`                        VARCHAR(64) PRIMARY KEY COMMENT '审批单号',
    `type`                      VARCHAR(32) NOT NULL COMMENT '审批类型：REFUND 等',
    `session_id`                VARCHAR(128) COMMENT '关联会话',
    `order_id`                  VARCHAR(64) COMMENT '关联订单号',
    `amount`                    VARCHAR(32) COMMENT '涉及金额',
    `reason`                    TEXT COMMENT '诉求原因',
    `created_at_ms`             BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `status`                    VARCHAR(16) NOT NULL COMMENT 'PENDING/APPROVED/DENIED',
    `operator`                  VARCHAR(64) COMMENT '决策操作员',
    `decision_note`             TEXT COMMENT '决策备注',
    `decided_at_ms`             BIGINT DEFAULT 0 COMMENT '决策时间戳（毫秒）',
    `execution_status`          VARCHAR(24) DEFAULT 'NOT_APPLICABLE' COMMENT '下游执行状态',
    `execution_failure_reason`  TEXT COMMENT '下游执行失败原因',
    `execution_attempts`        INT DEFAULT 0 COMMENT '下游执行尝试次数',
    INDEX `idx_approval_status` (`status`),
    INDEX `idx_approval_created` (`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工审批工单（退款放行等资金动作）';

-- 多轮槽位收集进度表（MybatisSlotFillingStore）。
CREATE TABLE IF NOT EXISTS `cw_slot_filling_progress` (
    `progress_key`    VARCHAR(191) PRIMARY KEY COMMENT '收集进度键：sessionId:formName',
    `asking`          VARCHAR(64) COMMENT '当前追问的槽位名',
    `collected_json`  TEXT COMMENT '已收集槽位值（JSON）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多轮槽位收集进度（如退款表单信息采集）';

-- 对话阶段状态机表（MybatisDialogStageStore）。
CREATE TABLE IF NOT EXISTS `cw_dialog_stage` (
    `session_id`  VARCHAR(191) PRIMARY KEY COMMENT '会话 ID',
    `stage`       VARCHAR(24) NOT NULL COMMENT '当前对话阶段：GREETING/COLLECTING/PROCESSING/CONFIRMING/ESCALATED'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话阶段状态机（多实例共享）';

-- 人机切换工单表（MybatisHandoffStore）。
CREATE TABLE IF NOT EXISTS `cw_handoff_ticket` (
    `id`                VARCHAR(64) PRIMARY KEY COMMENT '工单号',
    `session_id`        VARCHAR(128) COMMENT '关联会话',
    `reason`            TEXT COMMENT '转人工原因',
    `created_at_ms`     BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `status`            VARCHAR(16) NOT NULL COMMENT 'PENDING/CLAIMED/RESOLVED',
    `claimed_by`        VARCHAR(64) COMMENT '接单坐席',
    `claimed_at_ms`     BIGINT DEFAULT 0 COMMENT '接单时间戳（毫秒）',
    `resolution_note`   TEXT COMMENT '处理结果备注',
    `resolved_at_ms`    BIGINT DEFAULT 0 COMMENT '结案时间戳（毫秒）',
    INDEX `idx_handoff_status` (`status`),
    INDEX `idx_handoff_created` (`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人机切换工单（AI 转人工闭环）';

-- 消息级用户反馈表（MybatisFeedbackStore）。
CREATE TABLE IF NOT EXISTS `cw_message_feedback` (
    `message_id`     VARCHAR(64) PRIMARY KEY COMMENT '被反馈的消息ID',
    `session_id`     VARCHAR(128) COMMENT '所属会话',
    `type`           VARCHAR(8) NOT NULL COMMENT 'UP/DOWN',
    `comment`        TEXT COMMENT '文字说明',
    `created_at_ms`  BIGINT NOT NULL COMMENT '提交时间戳（毫秒，重复提交取最新）',
    INDEX `idx_feedback_session` (`session_id`),
    INDEX `idx_feedback_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息级用户反馈（点赞/点踩）';

-- 终端用户账户表（MybatisUserAccountStore）。
CREATE TABLE IF NOT EXISTS `cw_user` (
    `id`             VARCHAR(64) PRIMARY KEY COMMENT '用户ID',
    `username`       VARCHAR(64) NOT NULL COMMENT '用户名',
    `password_hash`  VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname`       VARCHAR(64) COMMENT '昵称',
    `phone`          VARCHAR(32) COMMENT '手机号',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `avatar_url`     VARCHAR(255) COMMENT '头像访问URL（相对路径，可为空）',
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服系统终端用户账户';

-- 客服工单主表（MybatisTicketStore）。
CREATE TABLE IF NOT EXISTS `cw_ticket` (
    `id`              VARCHAR(64) PRIMARY KEY COMMENT '工单号',
    `session_id`      VARCHAR(128) NOT NULL COMMENT '关联会话',
    `user_id`         VARCHAR(64) NOT NULL COMMENT '发起用户',
    `title`           VARCHAR(255) COMMENT '工单标题',
    `category`        VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT '分类',
    `priority`        VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '优先级',
    `status`          VARCHAR(32) NOT NULL COMMENT '状态机状态',
    `assignee`        VARCHAR(64) COMMENT '当前处理坐席',
    `handoff_reason`  VARCHAR(255) COMMENT '转人工原因',
    `resolve_note`    TEXT COMMENT '处理结论/备注',
    `reopen_count`    INT NOT NULL DEFAULT 0 COMMENT '重开次数',
    `created_at_ms`   BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`   BIGINT NOT NULL COMMENT '更新时间戳（毫秒）',
    `handoff_at_ms`   BIGINT DEFAULT 0 COMMENT '最近转人工时间戳（毫秒）',
    `claimed_at_ms`   BIGINT DEFAULT 0 COMMENT '接单时间戳（毫秒）',
    `resolved_at_ms`  BIGINT DEFAULT 0 COMMENT '解决时间戳（毫秒）',
    `closed_at_ms`    BIGINT DEFAULT 0 COMMENT '关闭时间戳（毫秒）',
    `last_user_active_at_ms` BIGINT DEFAULT 0 COMMENT '用户最后活跃时间戳（毫秒，空闲超时巡检基准）',
    INDEX `idx_ticket_session` (`session_id`),
    INDEX `idx_ticket_user` (`user_id`, `created_at_ms`),
    INDEX `idx_ticket_status` (`status`, `updated_at_ms`),
    INDEX `idx_ticket_assignee` (`assignee`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单（完整生命周期状态机）';

-- 工单事件轨迹表（MybatisTicketStore）。
CREATE TABLE IF NOT EXISTS `cw_ticket_event` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件自增主键',
    `ticket_id`      VARCHAR(64) NOT NULL COMMENT '所属工单号',
    `event_type`     VARCHAR(32) NOT NULL COMMENT '事件类型',
    `from_status`    VARCHAR(32) COMMENT '流转前状态',
    `to_status`      VARCHAR(32) COMMENT '流转后状态',
    `actor_type`     VARCHAR(16) NOT NULL COMMENT '动作发起方类型',
    `actor_id`       VARCHAR(64) COMMENT '动作发起方标识',
    `note`           VARCHAR(500) COMMENT '备注',
    `created_at_ms`  BIGINT NOT NULL COMMENT '事件时间戳（毫秒）',
    INDEX `idx_ticket_event` (`ticket_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单事件轨迹（不可变审计）';

-- 聊天消息留痕表（MybatisChatMessageStore）。
CREATE TABLE IF NOT EXISTS `cw_chat_message` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（游标翻页）',
    `message_id`     VARCHAR(64) NOT NULL COMMENT '业务消息号 MSG-<uuid>',
    `session_id`     VARCHAR(128) NOT NULL COMMENT '所属会话',
    `ticket_id`      VARCHAR(64) COMMENT '关联工单号（可空）',
    `sender_type`    VARCHAR(16) NOT NULL COMMENT '发送方类型 USER/BOT/AGENT/SYSTEM',
    `sender_id`      VARCHAR(64) COMMENT '发送方标识（可空）',
    `content`        TEXT NOT NULL COMMENT '消息内容',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    UNIQUE KEY `uk_chat_message_id` (`message_id`),
    INDEX `idx_chat_session` (`session_id`, `id`),
    INDEX `idx_chat_ticket` (`ticket_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话/工单聊天消息留痕';

-- 对话附件表（MybatisAttachmentStore）：上传附件落盘 + 落库，解析文本可追溯。
CREATE TABLE IF NOT EXISTS `cw_chat_attachment` (
    `id`             VARCHAR(64) NOT NULL COMMENT '附件ID(UUID)',
    `session_id`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '会话ID',
    `uploader`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '上传者标识',
    `channel`        VARCHAR(32) NOT NULL DEFAULT '' COMMENT '来源渠道:user_chat/admin_chat/vibecoding',
    `file_name`      VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `extension`      VARCHAR(16) NOT NULL DEFAULT '' COMMENT '扩展名',
    `mime_type`      VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'MIME类型',
    `file_size`      BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `storage_path`   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '落盘相对路径',
    `parse_status`   VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态:SUCCESS/FAILED',
    `parsed_text`    MEDIUMTEXT NULL COMMENT '解析出的文本',
    `error_message`  VARCHAR(512) NULL COMMENT '解析失败原因',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_cw_attachment_session` (`session_id`),
    INDEX `idx_cw_attachment_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话附件';

-- 商品表（MybatisProductBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_product` (
    `product_id`   VARCHAR(32) PRIMARY KEY COMMENT '商品ID',
    `name`         VARCHAR(128) NOT NULL COMMENT '商品名称',
    `category`     VARCHAR(64) COMMENT '品类',
    `price`        DECIMAL(10,2) NOT NULL COMMENT '价格',
    `stock`        INT NOT NULL DEFAULT 0 COMMENT '库存',
    `description`  VARCHAR(500) COMMENT '商品描述',
    `promotion`    VARCHAR(255) COMMENT '优惠活动',
    `status`       VARCHAR(16) NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE/OFF_SALE',
    INDEX `idx_product_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品（JDBC 后端演示表）';

INSERT IGNORE INTO `cw_product` (`product_id`, `name`, `category`, `price`, `stock`, `description`, `promotion`, `status`) VALUES
('P001', '旗舰款无线降噪耳机', '耳机', 299.00, 100, '旗舰款无线降噪耳机，蓝牙 5.3，续航 30 小时，支持多点连接，颜色 黑/白，质保 1 年', '满 300 减 50；可叠加新人券 20 元；下单送收纳包', 'ON_SALE'),
('P002', '运动防汗蓝牙耳机', '耳机', 199.00, 50, '运动防汗蓝牙耳机，IPX5 级防水，佩戴稳固，适合健身运动', '限时直降 30 元，晒单再返 10 元', 'ON_SALE'),
('P003', '商务降噪头戴耳机', '耳机', 599.00, 0, '商务降噪头戴耳机，主动降噪，麦克风通话清晰，续航 40 小时', '', 'ON_SALE');

-- 订单表（MybatisOrderBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_order` (
    `order_id`         VARCHAR(32) PRIMARY KEY COMMENT '订单号',
    `user_id`          VARCHAR(64) NOT NULL COMMENT '下单用户',
    `product_id`       VARCHAR(32) NOT NULL COMMENT '商品ID',
    `product_name`     VARCHAR(128) COMMENT '商品名称',
    `amount`           DECIMAL(10,2) NOT NULL COMMENT '订单金额',
    `status`           VARCHAR(32) NOT NULL COMMENT '订单状态',
    `receiver_addr`    VARCHAR(255) COMMENT '收货地址',
    `logistics_trace`  TEXT COMMENT '物流轨迹',
    `created_at_ms`    BIGINT NOT NULL COMMENT '下单时间戳（毫秒）',
    INDEX `idx_order_user` (`user_id`, `created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单（JDBC 后端演示表）';

INSERT IGNORE INTO `cw_order` (`order_id`, `user_id`, `product_id`, `product_name`, `amount`, `status`, `receiver_addr`, `logistics_trace`, `created_at_ms`) VALUES
('20260613001', 'U-demo-1', 'P001', '旗舰款无线降噪耳机', 299.00, '已发货', '北京市朝阳区建国路 88 号', '[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。', 1781049600000),
('20260613002', 'U-demo-1', 'P003', '商务降噪头戴耳机', 1599.00, '已签收', '上海市浦东新区世纪大道 100 号', '[5-18 已揽收]→[5-19 运输中]→[5-20 已签收]。', 1779235200000),
('20260613003', 'U-demo-2', 'P002', '运动防汗蓝牙耳机', 199.00, '待发货', '广州市天河区体育西路 1 号', '[6-13 已下单，仓库备货中]', 1781308800000),
('20260613004', 'U-demo-2', 'P001', '旗舰款无线降噪耳机', 299.00, '已退款', '深圳市南山区科技园路 5 号', '[6-08 已揽收]→[6-09 用户取消]→[6-10 已退款]', 1780876800000);

-- 售后工单表（MybatisAfterSalesBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_refund` (
    `refund_no`      VARCHAR(64) PRIMARY KEY COMMENT '售后工单号',
    `order_id`       VARCHAR(32) NOT NULL COMMENT '关联订单号',
    `type`           VARCHAR(16) NOT NULL COMMENT '类型：REFUND/RETURN/EXCHANGE',
    `status`         VARCHAR(16) NOT NULL COMMENT '状态：PENDING/APPROVED/DENIED',
    `amount`         DECIMAL(10,2) COMMENT '退款金额（退货/换货可空）',
    `reason`         VARCHAR(500) COMMENT '诉求原因',
    `new_spec`       VARCHAR(128) COMMENT '换货目标规格',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_refund_order` (`order_id`, `created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后工单（JDBC 后端演示表）';

INSERT IGNORE INTO `cw_refund` (`refund_no`, `order_id`, `type`, `status`, `amount`, `reason`, `new_spec`, `created_at_ms`) VALUES
('RF-seed-20260613004', '20260613004', 'REFUND', 'APPROVED', 299.00, '七天无理由退款', NULL, 1781049600000);

-- 发票申请表（MybatisAfterSalesBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_invoice_request` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '发票申请自增主键',
    `order_id`       VARCHAR(32) NOT NULL COMMENT '关联订单号',
    `invoice_title`  VARCHAR(255) NOT NULL COMMENT '发票抬头',
    `status`         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/ISSUED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_invoice_order` (`order_id`, `created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票申请（JDBC 后端演示表）';

-- 会员表（MybatisMemberBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_member` (
    `member_id`        VARCHAR(64) PRIMARY KEY COMMENT '会员ID（对应用户ID）',
    `level`            VARCHAR(32) NOT NULL COMMENT '会员等级',
    `points`           INT NOT NULL DEFAULT 0 COMMENT '当前积分',
    `points_expiring`  INT NOT NULL DEFAULT 0 COMMENT '本月底到期积分',
    `benefits`         VARCHAR(255) COMMENT '等级权益',
    `next_level`       VARCHAR(32) COMMENT '下一等级',
    `upgrade_gap`      DECIMAL(10,2) DEFAULT 0 COMMENT '升级所需再消费金额',
    `phone`            VARCHAR(32) COMMENT '注册手机号'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员（JDBC 后端演示表）';

INSERT IGNORE INTO `cw_member` (`member_id`, `level`, `points`, `points_expiring`, `benefits`, `next_level`, `upgrade_gap`, `phone`) VALUES
('U-demo-1', '黄金会员', 1280, 200, '免运费、专属客服、生日双倍积分', '铂金', 500.00, '138****0001'),
('U-demo-2', '白银会员', 320, 0, '满额包邮、积分商城兑换', '黄金', 800.00, '139****0002');

-- 会员账户问题处理日志表（MybatisMemberBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_member_account_log` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '处理日志自增主键',
    `issue`          VARCHAR(255) NOT NULL COMMENT '账户问题描述',
    `handling`       VARCHAR(500) COMMENT '处置话术',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_account_log_created` (`created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员账户问题处理日志（JDBC 后端演示表）';

-- 投诉工单表（MybatisComplaintBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_complaint` (
    `complaint_no`   VARCHAR(64) PRIMARY KEY COMMENT '投诉工单号',
    `order_id`       VARCHAR(32) COMMENT '关联订单号（可空）',
    `content`        TEXT COMMENT '投诉内容',
    `status`         VARCHAR(16) NOT NULL COMMENT '状态：PROCESSING/RESOLVED',
    `created_at_ms`  BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    INDEX `idx_complaint_order` (`order_id`, `created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉工单（JDBC 后端演示表）';

INSERT IGNORE INTO `cw_complaint` (`complaint_no`, `order_id`, `content`, `status`, `created_at_ms`) VALUES
('CP-seed-0001', '20260613002', '物流配送太慢，希望加快处理', 'PROCESSING', 1779235200000);

-- 知识库 FAQ 表（MybatisKnowledgeBackend 演示表）。
CREATE TABLE IF NOT EXISTS `cw_knowledge` (
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识条目自增主键',
    `keyword`    VARCHAR(255) NOT NULL COMMENT '命中关键词（逗号分隔）',
    `title`      VARCHAR(255) NOT NULL COMMENT '条目标题',
    `content`    TEXT NOT NULL COMMENT '条目内容',
    `source`     VARCHAR(255) COMMENT '来源标注',
    UNIQUE KEY `uk_knowledge_title` (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库 FAQ（JDBC 后端演示表）';

INSERT IGNORE INTO `cw_knowledge` (`keyword`, `title`, `content`, `source`) VALUES
('退货,退款,七天,无理由', '七天无理由退货政策', '支持七天无理由退货，商品需保持完好、不影响二次销售；定制类、生鲜类除外。', '《售后服务政策》第 3 条'),
('发票,开票,报销', '发票开具规则', '支持开具电子普通发票与增值税专用发票，可在订单详情页自助申请，1-3 个工作日开具。', '《发票管理规则》第 1 条'),
('运费,包邮,邮费', '运费说明', '单笔订单满 99 元包邮，偏远地区除外；退货运费由责任方承担。', '《运费说明》第 2 条');

-- 敏感词表（SensitiveWordFilter / cw_sensitive_word）：智能路由中控"一次拦截"词库。
-- 种子为脱敏占位词（非真实违禁词），覆盖 BLOCK/MASK/REVIEW 三种动作与多类目。
CREATE TABLE IF NOT EXISTS `cw_sensitive_word` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `word`           VARCHAR(128) NOT NULL COMMENT '敏感词原词面',
    `category`       VARCHAR(32) NOT NULL COMMENT '类目: POLITICS/PORN/ABUSE/COMPETITOR/CUSTOM',
    `action`         VARCHAR(16) NOT NULL COMMENT '处置动作: BLOCK/MASK/REVIEW',
    `enabled`        TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1启用/0停用',
    `created_at_ms`  BIGINT COMMENT '创建时间戳（毫秒）',
    `updated_at_ms`  BIGINT COMMENT '更新时间戳（毫秒）',
    UNIQUE KEY `uk_sensitive_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词表（一次拦截词库）';

INSERT IGNORE INTO `cw_sensitive_word` (`word`, `category`, `action`, `enabled`, `created_at_ms`, `updated_at_ms`) VALUES
('测试敏感词A', 'CUSTOM', 'BLOCK', 1, 1779235200000, 1779235200000),
('涉政占位', 'POLITICS', 'BLOCK', 1, 1779235200000, 1779235200000),
('辱骂占位', 'ABUSE', 'BLOCK', 1, 1779235200000, 1779235200000),
('竞品XX', 'COMPETITOR', 'MASK', 1, 1779235200000, 1779235200000),
('复核占位', 'CUSTOM', 'REVIEW', 1, 1779235200000, 1779235200000);
