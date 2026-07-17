-- =============================================================================
-- 代码知识库（Flyway V23，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 落地《AI编码助手需求文档》§4.11（P3-2 代码知识库问答，降级版）：对指定源码目录按类/方法级
-- 切块 → DashScope 真实 Embedding → 向量入库，应用层余弦相似度做语义 top-k 检索与检索增强问答。
-- 降级说明：向量以 JSON 数组存 MEDIUMTEXT、相似度在应用层计算，数据量万级以内合理；升级路径是
-- 接入真正的向量数据库（Milvus/pgvector 等），届时 embedding 列与相似度检索下沉到向量库。
-- 入口挂"工作区"（复用 workspace 权限），无需新建菜单。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_code_knowledge_index` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `index_name`      VARCHAR(128) NOT NULL COMMENT '索引名（唯一，用户可读标识）',
    `source_path`     VARCHAR(1024) NOT NULL COMMENT '被索引的源码目录/文件路径',
    `embedding_model` VARCHAR(64) COMMENT 'Embedding 模型名（如 text-embedding-v3）',
    `dimensions`      INT COMMENT '向量维度',
    `chunk_count`     INT NOT NULL DEFAULT 0 COMMENT '已入库分块数（构建进度）',
    `status`          VARCHAR(16) NOT NULL COMMENT '状态：BUILDING 构建中 / READY 就绪 / FAILED 失败',
    `message`         VARCHAR(512) COMMENT '状态说明（失败原因等）',
    `create_by`       BIGINT COMMENT '创建人用户ID',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT COMMENT '更新人用户ID',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_ai_code_knowledge_index_name` (`index_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码知识库索引';

CREATE TABLE IF NOT EXISTS `ai_code_knowledge_chunk` (
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `index_id`     BIGINT NOT NULL COMMENT '所属索引ID',
    `source_path`  VARCHAR(1024) NOT NULL COMMENT '来源文件相对路径',
    `chunk_index`  INT NOT NULL COMMENT '同一文件内的分块序号（0起）',
    `symbol`       VARCHAR(255) COMMENT '分块对应的符号（类/方法名等，可空）',
    `lang`         VARCHAR(32) COMMENT '语言标识（java/xml/...）',
    `content`      MEDIUMTEXT NOT NULL COMMENT '分块文本内容',
    `embedding`    MEDIUMTEXT NOT NULL COMMENT '向量（JSON 浮点数组）',
    `dimensions`   INT NOT NULL COMMENT '向量维度',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_ai_code_knowledge_chunk_index` (`index_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码知识库分块与向量';
