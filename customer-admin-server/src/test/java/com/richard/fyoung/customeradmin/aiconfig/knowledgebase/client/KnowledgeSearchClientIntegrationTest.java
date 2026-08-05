package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customerwork.rag.search.KnowledgeBaseEndpoint;
import com.richard.fyoung.customerwork.rag.search.KnowledgeNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link KnowledgeSearchClient} 门控集成测试：本机 RAG 服务（localhost:20002）可达且提供了
 * 真实凭据时才真正发一次检索，否则自动跳过（同仓库既有门控约定）。
 *
 * <p>凭据不入库不硬编码，从环境变量取：{@code RAG_IT_BASE_URL}（默认 http://localhost:20002）、
 * {@code RAG_IT_APP_ID}、{@code RAG_IT_API_KEY}。后两者缺失即跳过——本用例的价值在于验证
 * 真实接口契约（HTTP 200 + code=OK + data.nodes 结构），拿假 Key 跑没有意义。</p>
 * @author owlzhangfq@gmail.com
 */
class KnowledgeSearchClientIntegrationTest {

    private static final String DEFAULT_BASE_URL = "http://localhost:20002";
    private static final int DEFAULT_PORT = 20002;
    private static final int PROBE_TIMEOUT_MILLIS = 800;

    @Test
    void searchOne_shouldHitRealRagService() {
        String baseUrl = envOrDefault("RAG_IT_BASE_URL", DEFAULT_BASE_URL);
        String appId = System.getenv("RAG_IT_APP_ID");
        String apiKey = System.getenv("RAG_IT_API_KEY");
        assumeTrue(appId != null && apiKey != null, "未提供 RAG_IT_APP_ID / RAG_IT_API_KEY，跳过该集成测试");
        assumeTrue(reachable(), "RAG 服务不可达（" + DEFAULT_BASE_URL + "），跳过该集成测试");

        AdminRagProperties properties = new AdminRagProperties();
        KnowledgeSearchClient client = new KnowledgeSearchClient(new KnowledgeBaseHttpGuard(properties), properties);
        KnowledgeBaseEndpoint endpoint = new KnowledgeBaseEndpoint(1L, "it-kb", baseUrl, appId, apiKey,
            "application/json", "", 5, BigDecimal.ZERO);

        List<KnowledgeNode> nodes = client.searchOne(endpoint, properties.getTestQuery());

        // 召回 0 条也算连通成功（判成功的标准是 HTTP 200 + code=OK），只断言解析没炸
        assertNotNull(nodes);
    }

    private String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", DEFAULT_PORT), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
