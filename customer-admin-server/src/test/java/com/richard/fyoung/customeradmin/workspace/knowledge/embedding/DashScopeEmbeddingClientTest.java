package com.richard.fyoung.customeradmin.workspace.knowledge.embedding;

import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminKnowledgeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * admin 侧薄壳单测：只覆盖本层职责——API Key 来源解析（默认行优先、缺失 fast fail）与配置透传。
 *
 * <p>HTTP 调用与响应解析已下沉 starter
 * （{@code com.richard.fyoung.customerwork.data.knowledge.embedding.DashScopeEmbeddingClientTest} 覆盖），
 * 此处不重复。</p>
 * @author owlzhangfq@gmail.com
 */
class DashScopeEmbeddingClientTest {

    private static final String TEST_SECRET = "0123456789abcdef";

    private final AiModelConfigMapper modelConfigMapper = mock(AiModelConfigMapper.class);
    private final AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil(TEST_SECRET);
    private final AdminKnowledgeProperties properties = new AdminKnowledgeProperties();

    @Test
    void resolveApiKey_shouldPreferDefaultModelRow() {
        AiModelConfig first = config(1L, 0, cryptoUtil.encrypt("sk-first"));
        AiModelConfig defaultRow = config(2L, 1, cryptoUtil.encrypt("sk-default"));
        when(modelConfigMapper.selectList(any())).thenReturn(List.of(first, defaultRow));

        assertEquals("sk-default", client().resolveApiKey());
    }

    @Test
    void resolveApiKey_shouldFallbackToFirstEnabledRow() {
        AiModelConfig first = config(1L, 0, cryptoUtil.encrypt("sk-first"));
        when(modelConfigMapper.selectList(any())).thenReturn(List.of(first));

        assertEquals("sk-first", client().resolveApiKey());
    }

    @Test
    void resolveApiKey_shouldFastFail_whenNoDashScopeModelConfigured() {
        when(modelConfigMapper.selectList(any())).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> client().resolveApiKey());
        assertEquals(ResultCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED, ex.getResultCode());
    }

    @Test
    void resolveApiKey_shouldFastFail_whenDecryptedKeyBlank() {
        AiModelConfig blank = config(1L, 1, cryptoUtil.encrypt(""));
        when(modelConfigMapper.selectList(any())).thenReturn(List.of(blank));

        BizException ex = assertThrows(BizException.class, () -> client().resolveApiKey());
        assertEquals(ResultCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED, ex.getResultCode());
    }

    @Test
    void shouldExposeConfiguredDimensionsAndModelName() {
        properties.setDimensions(512);
        properties.setEmbeddingModel("text-embedding-v4");

        DashScopeEmbeddingClient client = client();
        assertEquals(512, client.dimensions());
        assertEquals("text-embedding-v4", client.modelName());
    }

    @Test
    void embedDocuments_shouldTranslateNotConfigured_withoutWrappingIntoGenericFailure() {
        when(modelConfigMapper.selectList(any())).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> client().embedDocuments(List.of("hello")));
        assertEquals(ResultCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED, ex.getResultCode());
    }

    @Test
    void embedQuery_shouldTranslateStarterFailureIntoBizException() {
        // 空查询文本在 starter 侧抛 IllegalStateException，薄壳负责转成 KNOWLEDGE_EMBEDDING_FAILED
        BizException ex = assertThrows(BizException.class, () -> client().embedQuery(" "));
        assertEquals(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, ex.getResultCode());
        assertEquals("查询文本为空", ex.getMessage());
    }

    private DashScopeEmbeddingClient client() {
        return new DashScopeEmbeddingClient(modelConfigMapper, cryptoUtil, properties);
    }

    private AiModelConfig config(Long id, Integer isDefault, String encryptedApiKey) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setIsDefault(isDefault);
        config.setApiKey(encryptedApiKey);
        return config;
    }
}
