package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpClientTools} 单测：起一个本地临时 {@link HttpServer}（JDK 内置，不引入 WireMock）验证
 * get/post/put/delete/postForm 的请求组装与响应解析，以及目标不可达时返回 error 字段而非抛异常。
 * @author owlzhangfq@gmail.com
 */
class HttpClientToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClientTools tools = new HttpClientTools();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 单一 handler：把"收到的方法 + 请求体"回显进响应体，供断言核对请求组装是否正确。
        server.createContext("/echo", exchange -> {
            String method = exchange.getRequestMethod();
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            String text = "method=" + method + ";body=" + new String(reqBody, StandardCharsets.UTF_8);
            byte[] resp = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getRequest_shouldReturn200AndEchoedMethod() throws Exception {
        JsonNode node = objectMapper.readTree(tools.getRequest(baseUrl, null).block());

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=GET"));
        assertTrue(node.get("error").isNull());
    }

    @Test
    void deleteRequest_shouldEchoDeleteMethod() throws Exception {
        JsonNode node = objectMapper.readTree(tools.deleteRequest(baseUrl, null).block());

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=DELETE"));
    }

    @Test
    void postRequest_shouldCarryJsonBody() throws Exception {
        JsonNode node = objectMapper.readTree(tools.postRequest(baseUrl, null, "hello-body").block());

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=POST"));
        assertTrue(node.get("body").asText().contains("hello-body"));
    }

    @Test
    void putRequest_shouldCarryJsonBody() throws Exception {
        JsonNode node = objectMapper.readTree(tools.putRequest(baseUrl, null, "put-body").block());

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=PUT"));
        assertTrue(node.get("body").asText().contains("put-body"));
    }

    @Test
    void postFormRequest_shouldEncodeFormParams() throws Exception {
        JsonNode node = objectMapper.readTree(
            tools.postFormRequest(baseUrl, Map.of("X-Trace", "1"), Map.of("name", "tom")).block());

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=POST"));
        assertTrue(node.get("body").asText().contains("name=tom"));
    }

    @Test
    void getRequest_shouldReturnErrorField_whenTargetUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String unreachable = "http://127.0.0.1:" + closedPort + "/nope";

        JsonNode node = objectMapper.readTree(tools.getRequest(unreachable, null).block());

        assertNotNull(node.get("error"));
        assertFalse(node.get("error").isNull());
        assertTrue(node.get("statusCode").isNull());
    }
}
