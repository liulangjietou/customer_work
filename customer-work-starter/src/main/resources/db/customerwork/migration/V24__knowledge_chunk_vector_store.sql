-- 受管知识库的向量分片，落客服端库。
--
-- 为什么落这边而不是继续留在后台库：运行时要读的表放客服端库，这是本仓库既有的落位规则
-- （内容风控三表、cw_tenant_quota 都是这么办的）。后台维护的整套企业知识库此前对
-- H5 终端用户完全不可达——C 端建 Agent 时挂的是 KnowledgeProvider，生产默认落到内置的
-- 4 条演示文本，而运营在后台做的版本管理、ACL、新鲜度门禁对线上对话零影响。
-- 让 starter 反向去读后台库是不成立的方向（跨库依赖反了），因此把数据放到这边，
-- 后台经 CustomerWorkFacade 写入。
--
-- 向量列为什么是 VARBINARY 而不是 JSON 文本：后台那版实现把向量以 JSON 存在 LONGTEXT 里，
-- 检索时逐条 ObjectMapper.readValue 解析。按 1024 维、1 万 chunk 估算，单次提问要解析约 40MB
-- 浮点文本，而且发生在请求线程上。定长 float32 编码把同一份数据压到约 4MB，
-- 读取只是一次 ByteBuffer 顺序扫描。16384 字节可容纳到 4096 维，覆盖当前所有在用的向量模型
-- （text-embedding-v3 是 1024 维 = 4096 字节）。
--
-- 字节序固定大端（见 VectorCodec）：这个选择一旦落库就不能改，改了会让存量向量整体读成噪声，
-- 而且不报错，只表现为检索结果突然毫不相关。
--
-- ACL 随分片冗余一份：检索发生在客服端，那边没有后台的文档修订表可 JOIN。
-- 冗余的代价是后台改 ACL 要同步刷新这边，收益是检索路径少一次跨库查询。

CREATE TABLE IF NOT EXISTS `cw_knowledge_chunk` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `kb_version_id`     BIGINT       NOT NULL COMMENT '知识库版本ID（对应后台 ai_knowledge_base_version.id）',
    `doc_revision_id`   BIGINT       NOT NULL COMMENT '文档修订ID，同时作为检索分区键与 ACL 归属',
    `chunk_index`       INT          NOT NULL COMMENT '分块序号',
    `content`           LONGTEXT     NOT NULL COMMENT '分块正文；检索打分阶段不读它，命中后才回查',
    `embedding`         VARBINARY(16384) NOT NULL COMMENT '定长 float32 向量，大端字节序，见 VectorCodec',
    `dimensions`        INT          NOT NULL COMMENT '向量维度；与 embedding 长度必须一致',
    `acl_mode`          VARCHAR(32)  NOT NULL DEFAULT 'PUBLIC' COMMENT '访问控制模式，随后台文档修订冗余',
    `external_id`       VARCHAR(255) NULL COMMENT '来源文档的外部标识，供回答溯源展示',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时间（毫秒）',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '更新时间（毫秒）',
    UNIQUE KEY `uk_cw_kb_chunk` (`doc_revision_id`, `chunk_index`),
    KEY `idx_cw_kb_chunk_version` (`tenant_id`, `kb_version_id`),
    KEY `idx_cw_kb_chunk_revision` (`doc_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='受管知识库向量分片（运行时检索用）';

-- 知识库版本在客服端的投影：C 端按智能体绑定的版本号检索，需要知道这个版本的检索参数。
-- 只投影检索真正要用的几项，后台那边的审核态、diff、制品绑定不过来。
CREATE TABLE IF NOT EXISTS `cw_knowledge_version` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `kb_version_id`     BIGINT       NOT NULL COMMENT '知识库版本ID（对应后台 ai_knowledge_base_version.id）',
    `kb_code`           VARCHAR(128) NOT NULL COMMENT '知识库编码',
    `kb_name`           VARCHAR(255) NOT NULL COMMENT '知识库名称，用于回答里的来源标注',
    `top_n`             INT          NOT NULL DEFAULT 3 COMMENT '召回条数上限',
    `score_threshold`   DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '相似度下限，低于它的命中直接丢弃',
    `dimensions`        INT          NOT NULL COMMENT '该版本向量维度；与查询向量维度不一致时检索必须失败而不是算出一个无意义的分数',
    `chunk_count`       INT          NOT NULL DEFAULT 0 COMMENT '分片总数，供后台核对投影是否完整',
    `synced_at_ms`      BIGINT       NOT NULL COMMENT '最近一次投影完成时间（毫秒）',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时间（毫秒）',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '更新时间（毫秒）',
    UNIQUE KEY `uk_cw_kb_version` (`kb_version_id`),
    KEY `idx_cw_kb_version_code` (`tenant_id`, `kb_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='受管知识库版本在客服端的检索投影';
