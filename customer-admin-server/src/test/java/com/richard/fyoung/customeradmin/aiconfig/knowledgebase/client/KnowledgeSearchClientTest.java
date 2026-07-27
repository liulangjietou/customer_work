package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * {@link KnowledgeSearchClient} 单测（全部离线，不依赖真实 RAG 服务）：
 * 响应解析、自定义头解析、阈值过滤、多库合并排序取 top-n、单库失败降级为空而不抛异常。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeSearchClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnowledgeSearchClient client;

    @BeforeEach
    void setUp() {
        client = new KnowledgeSearchClient(mock(KnowledgeBaseHttpGuard.class), new AdminRagProperties());
    }

    private KnowledgeBaseEndpoint endpoint(Long id, String name, int topN, String threshold) {
        return new KnowledgeBaseEndpoint(id, name, "http://localhost:20002", "app_" + id, "sk-test",
            "application/json", "", topN, new BigDecimal(threshold));
    }

    private KnowledgeNode node(String kbName, String content, String score) {
        return new KnowledgeNode(kbName, content, new BigDecimal(score), "doc-1", "chunk-1");
    }

    // ---- HTTP 协议版本与错误诊断 ----

    /**
     * 必须固定 HTTP/1.1：默认的 HTTP_2 会对 http:// 发 h2c upgrade 协商，
     * 实测自建 Node RAG 服务不响应该协商导致请求挂起到超时（HTTP_2 超时 8s vs HTTP_1_1 正常 928ms）。
     */
    @Test
    void httpClient_shouldPinHttp11_toAvoidH2cUpgradeHang() {
        assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, client.httpClientVersion(),
            "HTTP 版本必须固定为 1.1，否则对不支持 h2c 协商的服务会挂起到超时");
    }

    /** 连接类异常的 message 常为 null，必须翻译成可排查的提示（含 IPv6 only 的排查方向）。 */
    @Test
    void diagnose_shouldGiveActionableHint_forConnectException() {
        KnowledgeBaseEndpoint ep = endpoint(1L, "kb", 5, "0");

        String msg = client.diagnose(new java.net.ConnectException(), ep);

        assertTrue(msg.contains("无法建立连接"), "应说明是连接失败");
        assertTrue(msg.contains("http://localhost:20002"), "应带上实际地址便于核对");
        assertTrue(msg.contains("[::1]"), "应提示 IPv6 only 的排查方向");
    }

    /** 超时提示要给出当前超时值与可调配置项，并点出 HTTP/2 协商这个隐蔽成因。 */
    @Test
    void diagnose_shouldGiveActionableHint_forTimeout() {
        KnowledgeBaseEndpoint ep = endpoint(1L, "kb", 5, "0");

        String msg = client.diagnose(new java.net.http.HttpTimeoutException("request timed out"), ep);

        assertTrue(msg.contains("请求超时"), "应说明是超时");
        assertTrue(msg.contains("admin.rag.request-timeout-seconds"), "应给出可调的配置项");
    }

    /** 其余异常保留原始 message；message 为空时退回类名，不能出现 "null"。 */
    @Test
    void diagnose_shouldFallbackToClassName_whenMessageBlank() {
        KnowledgeBaseEndpoint ep = endpoint(1L, "kb", 5, "0");

        assertEquals("boom", client.diagnose(new IllegalStateException("boom"), ep));
        assertEquals("IllegalStateException", client.diagnose(new IllegalStateException(), ep));
    }

    // ---- 响应解析 ----

    @Test
    void parseNodes_shouldExtractContentScoreAndIds() throws Exception {
        String body = """
            {"code":"OK","message":"success","data":{"nodes":[
              {"content":"公积金提取流程","score":0.183,"doc_id":"d1","chunk_id":"c1","metadata":{}},
              {"content":"公积金缴存比例","score":0.131,"doc_id":"d2","chunk_id":"c2"}
            ],"request_id":"r1"},"request_id":"r1"}
            """;

        List<KnowledgeNode> nodes = KnowledgeSearchClient.parseNodes(MAPPER, "产品知识库", body);

        assertEquals(2, nodes.size());
        assertEquals("公积金提取流程", nodes.get(0).content());
        assertEquals("产品知识库", nodes.get(0).kbName());
        assertEquals("d1", nodes.get(0).docId());
        assertEquals("c1", nodes.get(0).chunkId());
        assertEquals(0, nodes.get(0).score().compareTo(new BigDecimal("0.183")));
    }

    @Test
    void parseNodes_shouldThrow_whenCodeIsNotOk() {
        String body = "{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid appkey\"}";

        BizException e = assertThrows(BizException.class,
            () -> KnowledgeSearchClient.parseNodes(MAPPER, "kb", body));
        assertTrue(e.getMessage().contains("UNAUTHORIZED"));
    }

    @Test
    void parseNodes_shouldReturnEmpty_whenNodesMissingOrContentBlank() throws Exception {
        assertTrue(KnowledgeSearchClient.parseNodes(MAPPER, "kb", "{\"code\":\"OK\",\"data\":{}}").isEmpty());
        assertTrue(KnowledgeSearchClient.parseNodes(MAPPER, "kb",
            "{\"code\":\"OK\",\"data\":{\"nodes\":[{\"content\":\"\",\"score\":0.5}]}}").isEmpty());
    }

    // ---- 自定义请求头 ----

    @Test
    void parseExtraHeaders_shouldParseJsonObject() {
        Map<String, String> headers = KnowledgeSearchClient.parseExtraHeaders(MAPPER, "{\"X-Tenant\":\"acme\"}");

        assertEquals(Map.of("X-Tenant", "acme"), headers);
    }

    @Test
    void parseExtraHeaders_shouldReturnEmpty_whenBlank() {
        assertTrue(KnowledgeSearchClient.parseExtraHeaders(MAPPER, null).isEmpty());
        assertTrue(KnowledgeSearchClient.parseExtraHeaders(MAPPER, "  ").isEmpty());
    }

    @Test
    void parseExtraHeaders_shouldRejectMalformedOrNonObjectJson() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeSearchClient.parseExtraHeaders(MAPPER, "{not-json"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeSearchClient.parseExtraHeaders(MAPPER, "[1,2]"));
    }

    @Test
    void parseExtraHeaders_shouldIgnoreReservedHeaders() {
        // Authorization / Content-Type 由客户端按知识库配置统一设置，JDK HttpRequest 的 header() 是追加而非覆盖
        Map<String, String> headers = KnowledgeSearchClient.parseExtraHeaders(MAPPER,
            "{\"authorization\":\"Bearer hack\",\"Content-Type\":\"text/plain\",\"X-Ok\":\"1\"}");

        assertEquals(Map.of("X-Ok", "1"), headers);
    }

    // ---- 多库并发检索：过滤 / 合并 / 降级 ----

    @Test
    void searchAll_shouldReturnEmpty_whenNoEndpointsOrBlankQuery() {
        assertTrue(client.searchAll(List.of(), "问题").isEmpty());
        assertTrue(client.searchAll(List.of(endpoint(1L, "kb1", 5, "0")), "  ").isEmpty());
    }

    @Test
    void searchAll_shouldDropNodesBelowThreshold() {
        KnowledgeSearchClient spyClient = spy(client);
        KnowledgeBaseEndpoint ep = endpoint(1L, "kb1", 5, "0.15");
        doReturn(List.of(node("kb1", "高分", "0.183"), node("kb1", "低分", "0.131")))
            .when(spyClient).searchOne(eq(ep), anyString());

        List<KnowledgeNode> nodes = spyClient.searchAll(List.of(ep), "问题");

        assertEquals(1, nodes.size());
        assertEquals("高分", nodes.get(0).content());
    }

    @Test
    void searchAll_shouldKeepAll_whenThresholdIsZero() {
        // rerank 分数量级只有 0.1x，默认阈值必须是 0（不过滤），否则常见的 0.5 会把全部召回丢光
        KnowledgeSearchClient spyClient = spy(client);
        KnowledgeBaseEndpoint ep = endpoint(1L, "kb1", 5, "0");
        doReturn(List.of(node("kb1", "a", "0.183"), node("kb1", "b", "0.131")))
            .when(spyClient).searchOne(eq(ep), anyString());

        assertEquals(2, spyClient.searchAll(List.of(ep), "问题").size());
    }

    @Test
    void searchAll_shouldMergeMultipleKnowledgeBases_sortedByScoreDescAndLimited() {
        KnowledgeSearchClient spyClient = spy(client);
        KnowledgeBaseEndpoint first = endpoint(1L, "kb1", 2, "0");
        KnowledgeBaseEndpoint second = endpoint(2L, "kb2", 3, "0");
        doReturn(List.of(node("kb1", "a", "0.11"), node("kb1", "c", "0.19"))).when(spyClient).searchOne(eq(first), anyString());
        doReturn(List.of(node("kb2", "b", "0.15"), node("kb2", "d", "0.13"))).when(spyClient).searchOne(eq(second), anyString());

        List<KnowledgeNode> nodes = spyClient.searchAll(List.of(first, second), "问题");

        // 合并上限取各库 topN 的最大值（3），按 score 倒排：0.19 / 0.15 / 0.13
        assertEquals(3, nodes.size());
        assertEquals(List.of("c", "b", "d"), nodes.stream().map(KnowledgeNode::content).toList());
    }

    @Test
    void searchAll_shouldDegradeToPartialResult_whenOneKnowledgeBaseFails() {
        KnowledgeSearchClient spyClient = spy(client);
        KnowledgeBaseEndpoint healthy = endpoint(1L, "kb1", 5, "0");
        KnowledgeBaseEndpoint broken = endpoint(2L, "kb2", 5, "0");
        doReturn(List.of(node("kb1", "ok", "0.18"))).when(spyClient).searchOne(eq(healthy), anyString());
        doThrow(new IllegalStateException("connection refused")).when(spyClient).searchOne(eq(broken), anyString());

        List<KnowledgeNode> nodes = assertDoesNotThrow(() -> spyClient.searchAll(List.of(healthy, broken), "问题"));

        assertEquals(1, nodes.size(), "单库失败只能损失该库的召回，不得打断整体检索");
        assertEquals("ok", nodes.get(0).content());
    }

    @Test
    void searchAll_shouldReturnEmptyAndNotThrow_whenAllKnowledgeBasesFail() {
        KnowledgeSearchClient spyClient = spy(client);
        doThrow(new IllegalStateException("boom")).when(spyClient).searchOne(any(KnowledgeBaseEndpoint.class), anyString());

        List<KnowledgeNode> nodes = assertDoesNotThrow(
            () -> spyClient.searchAll(List.of(endpoint(1L, "kb1", 5, "0")), "问题"));

        assertTrue(nodes.isEmpty(), "检索失败绝不抛异常打断对话");
    }
}
