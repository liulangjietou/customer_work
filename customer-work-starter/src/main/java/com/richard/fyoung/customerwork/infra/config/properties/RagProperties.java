package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RAG 配置。 */
@Data
public class RagProperties {
    /** 是否启用 RAG。 */
    private boolean enabled = true;
    /**
     * 知识库实现：memory（内置内存关键词）| simple（百炼 Embedding + 内存向量库，真实语义检索）
     * | bailian（百炼企业知识库）| dify | ragflow | haystack。
     */
    private String provider = "memory";
    /** 召回条数上限。 */
    private int topK = 3;
    /** simple 向量 RAG 配置（provider=simple 时生效）。 */
    private final Simple simple = new Simple();
    /** 百炼企业知识库配置（provider=bailian 时生效）。 */
    private final Bailian bailian = new Bailian();
    /** Dify 知识库配置（provider=dify 时生效）。 */
    private final Dify dify = new Dify();

    @Data
    public static class Dify {
        private String apiKey = "";
        private String apiBaseUrl = "";
        private String datasetId = "";
        private boolean enableRerank = true;
    }

    /** 真实 Embedding 向量 RAG：使用 model.embedding-name 与 model.api-key。 */
    @Data
    public static class Simple {
        /** 向量维度（text-embedding-v3 默认 1024）。 */
        private int dimensions = 1024;
    }

    @Data
    public static class Bailian {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String workspaceId = "";
        /** 知识库索引 ID（在百炼控制台创建）。 */
        private String indexId = "";
        private String endpoint = "";
        private boolean enableReranking = true;
    }
}
