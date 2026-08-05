package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpClientTools} 单测：本类是 starter {@code HttpClientDevToolOps} 的薄壳，故这里只验证
 * <b>属于薄壳的两件事</b>——5 个 {@code @Tool} 方法各自映射到正确的执行方法（起本地临时
 * {@link HttpServer} 回显方法名核对），以及安全拦截异常被转译成 {@link BizException}。
 * 请求组装、禁重定向、结果序列化等执行核心行为在 starter 的 {@code HttpClientDevToolOpsTest} 覆盖。
 * @author owlzhangfq@gmail.com
 */
class HttpClientToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    // 本地临时 server 监听 127.0.0.1（环回），默认模式会被 SSRF 收口拦截；
    // 用白名单模式显式放行 127.0.0.1，让工具方法映射用例照常验证。
    private final HttpClientTools tools = new HttpClientTools(loopbackAllowedGuard());

    private static SystemToolHttpGuard loopbackAllowedGuard() {
        AdminSystemToolProperties properties = new AdminSystemToolProperties();
        properties.getHttp().setAllowedHosts(List.of("127.0.0.1"));
        return new SystemToolHttpGuard(properties);
    }

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 单一 handler：把"收到的方法 + 请求体"回显进响应体，供断言核对工具方法映射是否正确。
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
    void blockedTarget_shouldRaiseBizExceptionThroughMono() {
        // 默认模式（空白名单）：环回地址被 SSRF 收口拦截，starter 异常在本壳里转成业务异常
        HttpClientTools defaultTools = new HttpClientTools(new SystemToolHttpGuard(new AdminSystemToolProperties()));

        BizException ex = assertThrows(BizException.class, () -> defaultTools.getRequest(baseUrl, null).block());
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, ex.getResultCode());
    }
}
