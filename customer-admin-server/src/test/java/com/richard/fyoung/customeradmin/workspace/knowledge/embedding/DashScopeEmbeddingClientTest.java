package com.richard.fyoung.customeradmin.workspace.knowledge.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DashScopeEmbeddingClient#parseEmbeddings} 单测：按 text_index 回填顺序、条数不匹配与错误响应。
 * @author owlzhangfq@gmail.com
 */
class DashScopeEmbeddingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
        BizException ex = assertThrows(BizException.class,
            () -> DashScopeEmbeddingClient.parseEmbeddings(mapper, body, 2));
        assertEquals(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, ex.getResultCode());
    }

    @Test
    void errorResponseSurfacesMessage() {
        String body = "{\"code\":\"InvalidApiKey\",\"message\":\"invalid key\"}";
        BizException ex = assertThrows(BizException.class,
            () -> DashScopeEmbeddingClient.parseEmbeddings(mapper, body, 1));
        assertEquals(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, ex.getResultCode());
        assertEquals("invalid key", ex.getMessage());
    }
}
