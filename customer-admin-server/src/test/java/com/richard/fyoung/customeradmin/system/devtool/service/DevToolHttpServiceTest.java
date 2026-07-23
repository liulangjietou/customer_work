package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.aiconfig.systemtool.tool.SystemToolHttpGuard;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendResponse;
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
 * {@link DevToolHttpService} 单测：起本地临时 {@link HttpServer}（JDK 内置，不引入 WireMock）验证
 * 常用方法的请求组装、响应解析、大响应截断、重定向不跟随，以及目标不可达返回 error 字段而非抛异常；
 * 另验证 SSRF 收口（默认拒环回）与协议校验的 fast fail 行为。
 * @author owlzhangfq@gmail.com
 */
class DevToolHttpServiceTest {

    // 本地临时 server 监听 127.0.0.1（环回），默认模式会被 SSRF 收口拦截；
    // 用白名单模式显式放行 127.0.0.1，让请求组装/响应解析用例照常验证。
    private final DevToolHttpService service = new DevToolHttpService(loopbackAllowedGuard());

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

    private DevToolHttpSendRequest request(String method, String url) {
        DevToolHttpSendRequest req = new DevToolHttpSendRequest();
        req.setMethod(method);
        req.setUrl(url);
        return req;
    }

    private static DevToolHttpSendRequest.HeaderItem header(String name, String value) {
        DevToolHttpSendRequest.HeaderItem item = new DevToolHttpSendRequest.HeaderItem();
        item.setName(name);
        item.setValue(value);
        return item;
    }

    @Test
    void get_shouldReturn200WithBodyAndHeaders() {
        DevToolHttpSendResponse resp = service.send(request("GET", baseUrl + "/echo"));

        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.getBody().contains("method=GET"));
        assertNotNull(resp.getHeaders());
        assertEquals(List.of("yes"), resp.getHeaders().get("x-echo-resp"));
        assertTrue(resp.getDurationMs() >= 0);
        assertNull(resp.getError());
        assertFalse(resp.isBodyTruncated());
    }

    @Test
    void post_shouldSendBodyAndCustomHeader() {
        DevToolHttpSendRequest req = request("POST", baseUrl + "/echo");
        req.setHeaders(List.of(header("X-Devtool-Test", "hello"), header("Content-Type", "application/json")));
        req.setBody("{\"a\":1}");

        DevToolHttpSendResponse resp = service.send(req);

        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.getBody().contains("method=POST"));
        assertTrue(resp.getBody().contains("header=hello"));
        assertTrue(resp.getBody().contains("body={\"a\":1}"));
    }

    @Test
    void patch_shouldBeSupported() {
        DevToolHttpSendRequest req = request("PATCH", baseUrl + "/echo");
        req.setBody("patched");

        DevToolHttpSendResponse resp = service.send(req);

        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.getBody().contains("method=PATCH"));
        assertTrue(resp.getBody().contains("body=patched"));
    }

    @Test
    void get_shouldIgnoreBodyEvenIfProvided() {
        DevToolHttpSendRequest req = request("GET", baseUrl + "/echo");
        req.setBody("should-not-send");

        DevToolHttpSendResponse resp = service.send(req);

        assertTrue(resp.getBody().contains("body=;") || resp.getBody().endsWith("body="));
    }

    @Test
    void head_shouldReturnStatusWithoutBody() {
        DevToolHttpSendResponse resp = service.send(request("HEAD", baseUrl + "/echo"));

        assertEquals(200, resp.getStatusCode());
        assertEquals("", resp.getBody());
        assertNull(resp.getError());
    }

    @Test
    void largeResponse_shouldBeTruncatedWithRealSizeReported() {
        DevToolHttpSendResponse resp = service.send(request("GET", baseUrl + "/large"));

        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.isBodyTruncated());
        assertEquals(1_048_577L, resp.getBodyBytes());
        assertEquals(1_048_576, resp.getBody().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void redirect_shouldNotFollowAndReturnLocation() {
        DevToolHttpSendResponse resp = service.send(request("GET", baseUrl + "/redirect"));

        assertEquals(302, resp.getStatusCode());
        assertEquals("/echo", resp.getRedirectLocation());
    }

    @Test
    void restrictedHeader_shouldBeSkippedSilently() {
        DevToolHttpSendRequest req = request("GET", baseUrl + "/echo");
        req.setHeaders(List.of(header("Host", "evil.example.com"), header("X-Devtool-Test", "kept")));

        DevToolHttpSendResponse resp = service.send(req);

        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.getBody().contains("header=kept"));
    }

    @Test
    void unreachableTarget_shouldReturnErrorFieldInsteadOfThrowing() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        DevToolHttpSendResponse resp = service.send(request("GET", "http://127.0.0.1:" + freePort + "/x"));

        assertNull(resp.getStatusCode());
        assertNotNull(resp.getError());
    }

    @Test
    void loopbackTarget_shouldBeBlockedByDefaultGuard() {
        // 默认模式（空白名单）：环回地址被 SSRF 收口拦截，fast fail 抛业务异常
        DevToolHttpService defaultService =
            new DevToolHttpService(new SystemToolHttpGuard(new AdminSystemToolProperties()));

        assertThrows(BizException.class, () -> defaultService.send(request("GET", baseUrl + "/echo")));
    }

    @Test
    void nonHttpScheme_shouldFastFail() {
        assertThrows(BizException.class, () -> service.send(request("GET", "ftp://127.0.0.1/file")));
    }
}
