-- =============================================================================
-- RAG 知识库检索（Flyway V41，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 落地"AI 配置 → 知识库管理"：一条 ai_knowledge_base 记录 = 一个外部 RAG 知识库
-- （base_url + app_id + api_key 同属一行，不做"服务/应用"两级拆分——外部接口本身就是
-- 按 app_id 分库检索，拆两级只会让配置更绕）。智能体通过 ai_agent_knowledge_base 多选绑定，
-- 运行时每轮对话自动检索并把召回内容注入用户消息（不是工具，模型无需决策是否调用）。
--
-- api_key 走 AES/GCM 加密存储（复用 AesGcmCryptoUtil，与 ai_model_config.api_key 同款），
-- 接口只回显掩码值；extra_headers 存 JSON 对象字符串（默认空串=无自定义头）。
--
-- score_threshold 默认 0：外部服务返回的是 rerank 分数，实测量级仅 0.13~0.19，
-- 按常见的 0.5 拍脑袋设阈值会把全部召回丢光。默认 0 = 不过滤（外部服务已按相关度排序），
-- 确有噪声时由使用方按自己知识库的真实分数分布调高。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：id 从 200 起（V38 显式 id 用到 195，200 起留足安全空间）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接
-- 返回全部权限点（沿用 V36/V38 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

-- 知识库配置：一行 = 一个外部 RAG 知识库（url + appkey + app_id）。
CREATE TABLE IF NOT EXISTS `ai_knowledge_base` (
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `kb_name`          VARCHAR(64) NOT NULL COMMENT '知识库名称（唯一标识，供智能体表单展示）',
    `base_url`         VARCHAR(255) NOT NULL COMMENT 'RAG 服务基址（不含 /api/v1/knowledge/search 路径）',
    `app_id`           VARCHAR(128) NOT NULL COMMENT '知识库应用ID（请求体 app_id）',
    `api_key`          VARCHAR(512) NOT NULL COMMENT 'AppKey（AES/GCM 加密存储，请求头 Authorization: Bearer）',
    `content_type`     VARCHAR(64) NOT NULL DEFAULT 'application/json' COMMENT '请求 Content-Type',
    `extra_headers`    VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '自定义请求头（JSON 对象字符串，空=无）',
    `top_n`            INT NOT NULL DEFAULT 5 COMMENT '单次检索返回条数（请求体 top_n）',
    `score_threshold`  DECIMAL(6,4) NOT NULL DEFAULT 0.0000 COMMENT '相关度阈值：低于该值的召回丢弃，0=不过滤（rerank 分数量级仅 0.1x）',
    `status`           TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 / 1启用',
    `test_status`      TINYINT NOT NULL DEFAULT 0 COMMENT '最近测试结果：0未测试 / 1成功 / 2失败',
    `test_time`        DATETIME COMMENT '最近测试时间',
    `remark`           VARCHAR(512) COMMENT '备注',
    `create_by`        BIGINT COMMENT '创建人ID',
    `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`        BIGINT COMMENT '更新人ID',
    `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_ai_kb_name` (`kb_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库配置';

-- 智能体-知识库关联（多选），结构对齐 ai_agent_mcp / ai_agent_skill。
CREATE TABLE IF NOT EXISTS `ai_agent_knowledge_base` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `agent_id`          BIGINT NOT NULL COMMENT '智能体ID',
    `knowledge_base_id` BIGINT NOT NULL COMMENT '知识库ID',
    UNIQUE KEY `uk_ai_agent_kb` (`agent_id`, `knowledge_base_id`),
    INDEX `idx_ai_agent_kb_base` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体-知识库关联';

-- 二级菜单：知识库管理（挂"AI 配置" id=2 下，sort=8 排在渠道接入 sort=7 之后）。菜单可见性即 view 权限点。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (200, 2, '知识库管理', 'knowledge-base:view', 1, '/aiconfig/knowledge-base', 'Collection', 'library', 8);

-- 三级：按钮/接口权限点（type=2，挂 200 下）。连通性测试复用 view，不单独发权限点。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (201, 200, '新增知识库', 'knowledge-base:add',    2, 1),
    (202, 200, '编辑知识库', 'knowledge-base:edit',   2, 2),
    (203, 200, '删除知识库', 'knowledge-base:delete', 2, 3);
