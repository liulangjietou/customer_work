package com.richard.fyoung.customerwork.data.knowledge.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DashScopeEmbeddingClient} 离线可测部分：响应解析（按 text_index 回填、条数不匹配、错误响应）
 * 与不触发 HTTP 的入参/配置分支。HTTP 层不在单测覆盖范围内。
 * @author owlzhangfq@gmail.com
 */
class DashScopeEmbeddingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 响应解析 ====================

    @Test
    void parsesAndReordersByTextIndex() throws Exception {
        String body = "{\"output\":{\"embeddings\":["
            + "{\"text_index\":1,\"embedding\":[0.1,0.2]},"
            + "{\"text_index\":0,\"embedding\":[0.3,0.4]}]}}";
        List<float[]> vectors = DashScopeEmbeddingClient.parseEmbeddings(mapper, body, 2);
        assertEquals(2, vectors.size());
        assertEquals(0.3f, vectors.get(0)[0], 1e-6);
        assertEquals(0.4f, vectors.get(0)[1], 1e-6);
        assertEquals(0.1f, vectors.get(1)[0], 1e-6);
        assertEquals(0.2f, vectors.get(1)[1], 1e-6);
    }

    @Test
    void countMismatchThrows() {
        String body = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0.1]}]}}";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> DashScopeEmbeddingClient.parseEmbeddings(mapper, body, 2));
        assertEquals("响应结构不合法", ex.getMessage());
    }

    @Test
    void errorResponseSurfacesMessage() {
        String body = "{\"code\":\"InvalidApiKey\",\"message\":\"invalid key\"}";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> DashScopeEmbeddingClient.parseEmbeddings(mapper, body, 1));
        assertEquals("invalid key", ex.getMessage());
    }

    @Test
    void outOfRangeTextIndexThrows() {
        String body = "{\"output\":{\"embeddings\":[{\"text_index\":5,\"embedding\":[0.1]}]}}";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> DashScopeEmbeddingClient.parseEmbeddings(mapper, body, 1));
        assertEquals("响应向量索引越界", ex.getMessage());
    }

    @Test
    void duplicatedTextIndexLeavesGapAndThrows() {
        // 两条都声明 text_index=0，第 2 个槽位缺失
        String body = "{\"output\":{\"embeddings\":["
            + "{\"text_index\":0,\"embedding\":[0.1]},"
            + "{\"text_index\":0,\"embedding\":[0.2]}]}}";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> DashScopeEmbeddingClient.parseEmbeddings(mapper, body, 2));
        assertEquals("响应缺失部分向量", ex.getMessage());
    }

    // ==================== 不触发 HTTP 的分支 ====================

    @Test
    void configAccessorsComeFromConstructor() {
        DashScopeEmbeddingClient client = client(() -> "sk-test");
        assertEquals(1024, client.dimensions());
        assertEquals("text-embedding-v3", client.modelName());
    }

    @Test
    void emptyTextsShortCircuits_withoutTouchingApiKey() {
        AtomicInteger supplierCalls = new AtomicInteger();
        DashScopeEmbeddingClient client = client(() -> {
            supplierCalls.incrementAndGet();
            return "sk-test";
        });
        assertTrue(client.embedDocuments(List.of()).isEmpty());
        assertTrue(client.embedDocuments(null).isEmpty());
        assertEquals(0, supplierCalls.get(), "空入参不应触发 Key 解析");
    }

    @Test
    void blankQueryTextFastFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> client(() -> "sk-test").embedQuery("  "));
        assertEquals("查询文本为空", ex.getMessage());
    }

    @Test
    void blankApiKeyFastFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> client(() -> "").embedQuery("hello"));
        assertEquals("DashScope API Key 未配置", ex.getMessage());
    }

    @Test
    void supplierExceptionPropagatesUnwrapped() {
        // Supplier 自带的业务语义异常必须原样上抛，不被包装成技术异常
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> client(() -> {
                throw new IllegalArgumentException("模型未配置");
            }).embedQuery("hello"));
        assertEquals("模型未配置", ex.getMessage());
    }

    private DashScopeEmbeddingClient client(java.util.function.Supplier<String> keySupplier) {
        return new DashScopeEmbeddingClient(keySupplier, "https://dashscope.aliyuncs.com/",
            "text-embedding-v3", 1024, 10);
    }
}
