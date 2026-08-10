package com.richard.fyoung.customerwork.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.safety.security.HttpTargetGuard;
import com.richard.fyoung.customerwork.safety.security.HttpTargetPolicy;
import com.richard.fyoung.customerwork.safety.security.InternalAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpClientDevToolOps} 单测：起一个本地临时 {@link HttpServer}（JDK 内置，不引入 WireMock）验证
 * get/post/put/delete/postForm 的请求组装与响应解析、目标不可达时返回 error 字段而非抛异常，
 * 以及 SSRF 收口与"不跟随重定向"这两条安全约束。
 * @author owlzhangfq@gmail.com
 */
class HttpClientDevToolOpsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 本地临时 server 监听 127.0.0.1（环回），拒内网策略会拦；用白名单显式放行 127.0.0.1，
    // 让请求组装/响应解析用例照常验证。
    private final HttpClientDevToolOps ops = new HttpClientDevToolOps(new HttpTargetGuard(
        HttpTargetPolicy.of(List.of("127.0.0.1"), InternalAddressPolicy.DENY_INTERNAL)));

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
    void get_shouldReturn200AndEchoedMethod() throws Exception {
        JsonNode node = objectMapper.readTree(ops.get(baseUrl, null));

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=GET"));
        assertTrue(node.get("error").isNull());
    }

    @Test
    void delete_shouldEchoDeleteMethod() throws Exception {
        JsonNode node = objectMapper.readTree(ops.delete(baseUrl, null));

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=DELETE"));
    }

    @Test
    void post_shouldCarryJsonBody() throws Exception {
        JsonNode node = objectMapper.readTree(ops.post(baseUrl, null, "hello-body"));

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=POST"));
        assertTrue(node.get("body").asText().contains("hello-body"));
    }

    @Test
    void put_shouldCarryJsonBody() throws Exception {
        JsonNode node = objectMapper.readTree(ops.put(baseUrl, null, "put-body"));

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=PUT"));
        assertTrue(node.get("body").asText().contains("put-body"));
    }

    @Test
    void postForm_shouldEncodeFormParams() throws Exception {
        JsonNode node = objectMapper.readTree(
            ops.postForm(baseUrl, Map.of("X-Trace", "1"), Map.of("name", "tom")));

        assertEquals(200, node.get("statusCode").asInt());
        assertTrue(node.get("body").asText().contains("method=POST"));
        assertTrue(node.get("body").asText().contains("name=tom"));
    }

    @Test
    void get_shouldReturnErrorField_whenTargetUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        JsonNode node = objectMapper.readTree(ops.get("http://127.0.0.1:" + closedPort + "/nope", null));

        assertNotNull(node.get("error"));
        assertFalse(node.get("error").isNull());
        assertTrue(node.get("statusCode").isNull());
    }

    @Test
    void get_shouldFastFail_whenTargetBlockedByGuard() {
        HttpClientDevToolOps guarded = new HttpClientDevToolOps(
            new HttpTargetGuard(HttpTargetPolicy.of(List.of(), InternalAddressPolicy.DENY_INTERNAL)));

        // 安全拦截不收敛进 error 字段，而是抛异常由调用壳转译成业务错误码
        assertThrows(HttpTargetForbiddenException.class, () -> guarded.get(baseUrl, null));
    }

    /**
     * SSRF 收口配套回归：GET 遇 302 不得自动跟随（Spring 对 GET 默认开自动跟随，被利用时
     * "合规域名 302 指向内网"可绕过 Guard）。断言 3xx 作为终态返回（带 location），且重定向目标从未被真实请求。
     */
    @Test
    void get_shouldNotFollowRedirect_andReturn3xxAsFinal() throws Exception {
        AtomicBoolean targetHit = new AtomicBoolean(false);
        HttpServer targetServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        targetServer.createContext("/secret", exchange -> {
            targetHit.set(true);
            byte[] resp = "secret-content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        targetServer.start();
        String targetUrl = "http://127.0.0.1:" + targetServer.getAddress().getPort() + "/secret";
        try {
            // 服务 A：302 指向服务 B（模拟攻击者可控的"合规"站点重定向到受保护地址）
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", targetUrl);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            String redirectUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

            JsonNode node = objectMapper.readTree(ops.get(redirectUrl, null));

            assertEquals(302, node.get("statusCode").asInt());
            assertEquals(targetUrl, node.get("location").asText());
            assertFalse(targetHit.get());
        } finally {
            targetServer.stop(0);
        }
    }
}
