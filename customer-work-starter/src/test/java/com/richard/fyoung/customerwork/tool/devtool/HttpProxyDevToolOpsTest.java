package com.richard.fyoung.customerwork.tool.devtool;

import com.richard.fyoung.customerwork.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.security.HttpTargetGuard;
import com.richard.fyoung.customerwork.security.HttpTargetPolicy;
import com.richard.fyoung.customerwork.security.InternalAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpProxyDevToolOps} 单测：起本地临时 {@link HttpServer}（JDK 内置，不引入 WireMock）验证
 * 常用方法的请求组装、响应解析、大响应截断、受限头跳过、重定向不跟随，以及目标不可达返回 error 字段
 * 而非抛异常；另验证 SSRF 收口与协议校验的 fast fail 行为。
 * @author owlzhangfq@gmail.com
 */
class HttpProxyDevToolOpsTest {

    // 本地临时 server 监听 127.0.0.1（环回），拒内网策略会拦；用白名单显式放行 127.0.0.1。
    private final HttpProxyDevToolOps ops = new HttpProxyDevToolOps(new HttpTargetGuard(
        HttpTargetPolicy.of(List.of("127.0.0.1"), InternalAddressPolicy.DENY_INTERNAL)));

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 回显 handler：把"收到的方法 + 指定请求头 + 请求体"写回响应体，供断言核对请求组装是否正确。
        server.createContext("/echo", exchange -> {
            String method = exchange.getRequestMethod();
            String customHeader = exchange.getRequestHeaders().getFirst("X-Devtool-Test");
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            String text = "method=" + method + ";header=" + customHeader
                + ";body=" + new String(reqBody, StandardCharsets.UTF_8);
            byte[] resp = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Echo-Resp", "yes");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        // 大响应 handler：回 1MB + 1 字节，验证截断
        server.createContext("/large", exchange -> {
            byte[] resp = new byte[1_048_577];
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        // 重定向 handler：302 指向 /echo，验证不自动跟随
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/echo");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static HttpProxyDevToolOps.HeaderPair header(String name, String value) {
        return new HttpProxyDevToolOps.HeaderPair(name, value);
    }

    @Test
    void get_shouldReturn200WithBodyAndHeaders() {
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("GET", baseUrl + "/echo", null, null);

        assertEquals(200, result.getStatusCode());
        assertTrue(result.getBody().contains("method=GET"));
        assertNotNull(result.getHeaders());
        assertEquals(List.of("yes"), result.getHeaders().get("x-echo-resp"));
        assertTrue(result.getDurationMs() >= 0);
        assertNull(result.getError());
        assertFalse(result.isBodyTruncated());
    }

    @Test
    void post_shouldSendBodyAndCustomHeader() {
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("POST", baseUrl + "/echo",
            List.of(header("X-Devtool-Test", "hello"), header("Content-Type", "application/json")),
            "{\"a\":1}");

        assertEquals(200, result.getStatusCode());
        assertTrue(result.getBody().contains("method=POST"));
        assertTrue(result.getBody().contains("header=hello"));
        assertTrue(result.getBody().contains("body={\"a\":1}"));
    }

    @Test
    void patch_shouldBeSupported() {
        // 选 JDK HttpClient 而非 RestTemplate 的理由：HttpURLConnection 不支持 PATCH
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("PATCH", baseUrl + "/echo", null, "patched");

        assertEquals(200, result.getStatusCode());
        assertTrue(result.getBody().contains("method=PATCH"));
        assertTrue(result.getBody().contains("body=patched"));
    }

    @Test
    void get_shouldIgnoreBodyEvenIfProvided() {
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("GET", baseUrl + "/echo", null, "should-not-send");

        assertTrue(result.getBody().contains("body=;") || result.getBody().endsWith("body="));
    }

    @Test
    void head_shouldReturnStatusWithoutBody() {
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("HEAD", baseUrl + "/echo", null, null);

        assertEquals(200, result.getStatusCode());
        assertEquals("", result.getBody());
        assertNull(result.getError());
    }

    @Test
    void largeResponse_shouldBeTruncatedWithRealSizeReported() {
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("GET", baseUrl + "/large", null, null);

        assertEquals(200, result.getStatusCode());
        assertTrue(result.isBodyTruncated());
        assertEquals(1_048_577L, result.getBodyBytes());
        assertEquals(1_048_576, result.getBody().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void redirect_shouldNotFollowAndReturnLocation() {
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("GET", baseUrl + "/redirect", null, null);

        assertEquals(302, result.getStatusCode());
        assertEquals("/echo", result.getRedirectLocation());
    }

    @Test
    void restrictedHeader_shouldBeSkippedSilently() {
        HttpProxyDevToolOps.HttpProxyResult result = ops.send("GET", baseUrl + "/echo",
            List.of(header("Host", "evil.example.com"), header("X-Devtool-Test", "kept")), null);

        assertEquals(200, result.getStatusCode());
        assertTrue(result.getBody().contains("header=kept"));
    }

    @Test
    void unreachableTarget_shouldReturnErrorFieldInsteadOfThrowing() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        HttpProxyDevToolOps.HttpProxyResult result =
            ops.send("GET", "http://127.0.0.1:" + freePort + "/x", null, null);

        assertNull(result.getStatusCode());
        assertNotNull(result.getError());
    }

    @Test
    void loopbackTarget_shouldBeBlockedByDenyInternalPolicy() {
        HttpProxyDevToolOps guarded = new HttpProxyDevToolOps(
            new HttpTargetGuard(HttpTargetPolicy.of(List.of(), InternalAddressPolicy.DENY_INTERNAL)));

        assertThrows(HttpTargetForbiddenException.class,
            () -> guarded.send("GET", baseUrl + "/echo", null, null));
    }

    @Test
    void nonHttpScheme_shouldFastFail() {
        assertThrows(HttpTargetForbiddenException.class,
            () -> ops.send("GET", "ftp://127.0.0.1/file", null, null));
    }
}
