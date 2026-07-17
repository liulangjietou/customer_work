package com.richard.fyoung.customeradmin.workspace.knowledge.embedding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminKnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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

/**
 * DashScope text-embedding 直连实现（P3-2）：走既有 {@code ai_model_config} 的 dashscope 行拿 API Key
 * （AES 解密），用 JDK 内置 {@link HttpClient} 调 DashScope 原生 embeddings 端点（与
 * {@code AdminModelFactory} 的探活手法一致，不引入额外 HTTP 依赖）。
 *
 * <p>为何直连 REST 而非用框架 {@code DashScopeTextEmbedding}：框架实现内部走 DashScope SDK，难以在单测里
 * mock 隔离；抽成 {@link EmbeddingClient} 接口 + REST 实现后，KnowledgeService 依赖接口，单测注入桩即可
 * 完全离线。Key 缺失时 fast fail 明确引导配置，绝不静默降级回关键词。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);
    private static final String PROVIDER_DASHSCOPE = "dashscope";
    private static final int STATUS_ENABLED = 1;
    private static final int IS_DEFAULT = 1;
    private static final String EMBEDDING_PATH = "/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final String TEXT_TYPE_DOCUMENT = "document";
    private static final String TEXT_TYPE_QUERY = "query";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final AiModelConfigMapper modelConfigMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AdminKnowledgeProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public DashScopeEmbeddingClient(AiModelConfigMapper modelConfigMapper, AesGcmCryptoUtil cryptoUtil,
                                    AdminKnowledgeProperties properties) {
        this.modelConfigMapper = modelConfigMapper;
        this.cryptoUtil = cryptoUtil;
        this.properties = properties;
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    @Override
    public String modelName() {
        return properties.getEmbeddingModel();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }
        String apiKey = resolveApiKey();
        List<float[]> vectors = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
            vectors.addAll(embedBatch(apiKey, batch, TEXT_TYPE_DOCUMENT));
        }
        return vectors;
    }

    @Override
    public float[] embedQuery(String text) {
        if (!StringUtils.hasText(text)) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, "查询文本为空");
        }
        return embedBatch(resolveApiKey(), List.of(text), TEXT_TYPE_QUERY).get(0);
    }

    /** 解析可用的 DashScope API Key：优先默认模型行，否则首个启用的 dashscope 行；均无则 fast fail。 */
    private String resolveApiKey() {
        List<AiModelConfig> candidates = modelConfigMapper.selectList(new LambdaQueryWrapper<AiModelConfig>()
            .eq(AiModelConfig::getProvider, PROVIDER_DASHSCOPE)
            .eq(AiModelConfig::getStatus, STATUS_ENABLED)
            .orderByDesc(AiModelConfig::getIsDefault)
            .orderByAsc(AiModelConfig::getId));
        if (CollectionUtils.isEmpty(candidates)) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED);
        }
        AiModelConfig config = candidates.stream()
            .filter(c -> Integer.valueOf(IS_DEFAULT).equals(c.getIsDefault()))
            .findFirst().orElse(candidates.get(0));
        String apiKey = cryptoUtil.decrypt(config.getApiKey());
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED);
        }
        return apiKey;
    }

    /** 单批调用 DashScope embeddings 端点并解析成向量列表。 */
    private List<float[]> embedBatch(String apiKey, List<String> texts, String textType) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                "model", properties.getEmbeddingModel(),
                "input", Map.of("texts", texts),
                "parameters", Map.of("text_type", textType, "dimension", properties.getDimensions())));
            String url = trimTrailingSlash(properties.getEmbeddingBaseUrl()) + EMBEDDING_PATH;
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
                throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED,
                    "DashScope embedding HTTP " + response.statusCode());
            }
            return parseEmbeddings(mapper, response.body(), texts.size());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[knowledge] embedding call failed, code={}", "KNOWLEDGE-EMBED-CALL", e);
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, e.getMessage());
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
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, msg);
        }
        float[][] ordered = new float[expectedCount][];
        for (JsonNode item : embeddings) {
            int textIndex = item.path("text_index").asInt();
            JsonNode vec = item.path("embedding");
            if (textIndex < 0 || textIndex >= expectedCount || !vec.isArray()) {
                throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, "响应向量索引越界");
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
                throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, "响应缺失部分向量");
            }
            result.add(vector);
        }
        return result;
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
