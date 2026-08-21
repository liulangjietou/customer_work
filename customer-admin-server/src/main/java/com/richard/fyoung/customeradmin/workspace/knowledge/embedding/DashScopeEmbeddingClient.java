package com.richard.fyoung.customeradmin.workspace.knowledge.embedding;

import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminKnowledgeProperties;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * DashScope Embedding 客户端的 admin 侧薄壳：HTTP 调用与响应解析在 starter 的
 * {@link com.richard.fyoung.customerwork.data.knowledge.embedding.DashScopeEmbeddingClient}（两侧唯一实现），
 * 本类只负责 admin 侧的装配契约：
 * <ul>
 *   <li>作为 Spring Bean 暴露，配置取 {@link AdminKnowledgeProperties}；</li>
 *   <li>把 API Key 来源接到既有 {@code ai_model_config} 的 dashscope 行（AES 解密），
 *       以 Supplier 形式注入 starter——Key 缺失时 fast fail 明确引导配置，绝不静默降级回关键词；</li>
 *   <li>错误语义转译：starter 抛技术异常 {@link IllegalStateException}，这里转成携带
 *       {@link ResultCode#KNOWLEDGE_EMBEDDING_FAILED} 的 {@link BizException}（本类是唯一转译点）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Component
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private final ModelConfigAccess modelConfigAccess;
    private final AesGcmCryptoUtil cryptoUtil;
    private final com.richard.fyoung.customerwork.data.knowledge.embedding.DashScopeEmbeddingClient delegate;

    public DashScopeEmbeddingClient(ModelConfigAccess modelConfigAccess, AesGcmCryptoUtil cryptoUtil,
                                    AdminKnowledgeProperties properties) {
        this.modelConfigAccess = modelConfigAccess;
        this.cryptoUtil = cryptoUtil;
        this.delegate = new com.richard.fyoung.customerwork.data.knowledge.embedding.DashScopeEmbeddingClient(
            this::resolveApiKey, properties.getEmbeddingBaseUrl(), properties.getEmbeddingModel(),
            properties.getDimensions(), properties.getBatchSize());
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        try {
            return delegate.embedDocuments(texts);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, e.getMessage());
        }
    }

    @Override
    public float[] embedQuery(String text) {
        try {
            return delegate.embedQuery(text);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, e.getMessage());
        }
    }

    /**
     * 解析可用的 DashScope API Key：优先默认模型行，否则首个启用的 dashscope 行；均无则 fast fail。
     * 包级可见便于离线单测。抛出的 {@link BizException} 会被上面的转译逻辑原样放行，
     * 保住 {@link ResultCode#KNOWLEDGE_EMBEDDING_NOT_CONFIGURED} 这一更精确的语义。
     */
    String resolveApiKey() {
        List<AiModelConfig> candidates = modelConfigAccess.listPreferredEnabled(ModelProviders.DASHSCOPE);
        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED);
        }
        AiModelConfig config = candidates.get(0);
        String apiKey = cryptoUtil.decrypt(config.getApiKey());
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED);
        }
        return apiKey;
    }
}
