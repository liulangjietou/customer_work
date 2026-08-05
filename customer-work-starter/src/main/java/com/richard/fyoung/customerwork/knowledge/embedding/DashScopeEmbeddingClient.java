package com.richard.fyoung.customerwork.knowledge.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * DashScope text-embedding 直连实现：用 JDK 内置 {@link HttpClient} 调 DashScope 原生 embeddings 端点
 * （不引入额外 HTTP 依赖）。
 *
 * <p>为何直连 REST 而非用框架 {@code DashScopeTextEmbedding}：框架实现内部走 DashScope SDK，难以在单测里
 * mock 隔离；抽成 {@link EmbeddingClient} 接口 + REST 实现后，调用方依赖接口，单测注入桩即可完全离线。</p>
 *
 * <p>API Key 由构造注入的 {@link Supplier} 提供，本类不感知 Key 从何而来（配置项/数据库/密钥服务皆可）。
 * Supplier 抛出的异常原样上抛，不做包装——Key 缺失时调用方能拿到自己的语义化异常，绝不静默降级。
 * 本类自身的失败统一抛 {@link IllegalStateException}（starter 不感知业务错误码体系），
 * 需要业务错误码的调用方在自己的唯一入口处转译一次即可。</p>
 * @author owlzhangfq@gmail.com
 */
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);
    private static final String EMBEDDING_PATH = "/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final String TEXT_TYPE_DOCUMENT = "document";
    private static final String TEXT_TYPE_QUERY = "query";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final Supplier<String> apiKeySupplier;
    private final String baseUrl;
    private final String embeddingModel;
    private final int dimensions;
    private final int batchSize;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param apiKeySupplier DashScope API Key 提供者（每次调用现取，支持热更新/轮换）
     * @param baseUrl        DashScope 原生端点 base-url（如 https://dashscope.aliyuncs.com）
     * @param embeddingModel Embedding 模型名（如 text-embedding-v3）
     * @param dimensions     向量维度
     * @param batchSize      单次请求的最大文本条数
     */
    public DashScopeEmbeddingClient(Supplier<String> apiKeySupplier, String baseUrl, String embeddingModel,
                                    int dimensions, int batchSize) {
        this.apiKeySupplier = apiKeySupplier;
        this.baseUrl = baseUrl;
        this.embeddingModel = embeddingModel;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return embeddingModel;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }
        String apiKey = resolveApiKey();
        List<float[]> vectors = new ArrayList<>(texts.size());
        int size = Math.max(1, batchSize);
        for (int start = 0; start < texts.size(); start += size) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + size));
            vectors.addAll(embedBatch(apiKey, batch, TEXT_TYPE_DOCUMENT));
        }
        return vectors;
    }

    @Override
    public float[] embedQuery(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("查询文本为空");
        }
        return embedBatch(resolveApiKey(), List.of(text), TEXT_TYPE_QUERY).get(0);
    }

    /** 取 API Key；Supplier 自身的异常原样上抛（调用方的"未配置"语义比这里的兜底更准确）。 */
    private String resolveApiKey() {
        String apiKey = apiKeySupplier.get();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("DashScope API Key 未配置");
        }
        return apiKey;
    }

    /** 单批调用 DashScope embeddings 端点并解析成向量列表。 */
    private List<float[]> embedBatch(String apiKey, List<String> texts, String textType) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                "model", embeddingModel,
                "input", Map.of("texts", texts),
                "parameters", Map.of("text_type", textType, "dimension", dimensions)));
            String url = trimTrailingSlash(baseUrl) + EMBEDDING_PATH;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("[knowledge] embedding http failed, code={}, status={}", "KNOWLEDGE-EMBED-HTTP",
                    response.statusCode());
                throw new IllegalStateException("DashScope embedding HTTP " + response.statusCode());
            }
            return parseEmbeddings(mapper, response.body(), texts.size());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[knowledge] embedding call failed, code={}", "KNOWLEDGE-EMBED-CALL", e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 解析 DashScope embeddings 响应（{@code {"output":{"embeddings":[{"text_index":0,"embedding":[...]}]}}}），
     * 按 text_index 回填到入参顺序。包级可见 + 静态，便于离线单测。
     */
    static List<float[]> parseEmbeddings(ObjectMapper mapper, String responseBody, int expectedCount) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode embeddings = root.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != expectedCount) {
            String msg = root.hasNonNull("message") ? root.get("message").asText() : "响应结构不合法";
            throw new IllegalStateException(msg);
        }
        float[][] ordered = new float[expectedCount][];
        for (JsonNode item : embeddings) {
            int textIndex = item.path("text_index").asInt();
            JsonNode vec = item.path("embedding");
            if (textIndex < 0 || textIndex >= expectedCount || !vec.isArray()) {
                throw new IllegalStateException("响应向量索引越界");
            }
            float[] vector = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                vector[i] = (float) vec.get(i).asDouble();
            }
            ordered[textIndex] = vector;
        }
        List<float[]> result = new ArrayList<>(expectedCount);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new IllegalStateException("响应缺失部分向量");
            }
            result.add(vector);
        }
        return result;
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
