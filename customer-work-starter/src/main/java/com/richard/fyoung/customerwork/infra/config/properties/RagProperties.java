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
     * 知识库实现，取值见 {@code KnowledgeProviders}：
     * memory（内置演示语料，<b>仅开发用</b>）| simple（百炼 Embedding + 内存向量库）
     * | bailian（百炼企业知识库）| dify。
     *
     * <p>此前这里还列了 {@code ragflow} 与 {@code haystack}，但 {@code KnowledgeProvider#build()}
     * 里并没有对应分支，配上去会落进 default 静默降级成演示语料。它们属于待实现的扩展点，
     * 已从取值清单移除，避免文档与实现两套说法。</p>
     *
     * <p><b>生产必须显式配置为非 memory 的取值</b>，否则 {@code ProductionReadinessValidator}
     * 会拒绝启动——memory 的语料是代码里硬编码的 4 条演示文本。</p>
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
