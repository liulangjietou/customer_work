package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.aiconfig.systemtool.tool.SystemToolHttpGuard;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevToolHttpService} 单测：本类是 starter {@code HttpProxyDevToolOps} 的薄壳，故这里只验证
 * <b>属于薄壳的两件事</b>——请求头 DTO ↔ starter 入参的转换与结果对象 → VO 的字段映射（起本地临时
 * {@link HttpServer} 走一遍真实调用核对），以及异常转译（安全拦截 → {@code SYSTEM_TOOL_HTTP_FORBIDDEN}）。
 * 受限头跳过、响应截断、重定向不跟随、异常翻译等执行核心行为在 starter 的
 * {@code HttpProxyDevToolOpsTest} 覆盖。
 * @author owlzhangfq@gmail.com
 */
class DevToolHttpServiceTest {

    // 本地临时 server 监听 127.0.0.1（环回），默认模式会被 SSRF 收口拦截；
    // 用白名单模式显式放行 127.0.0.1，让转换用例照常验证。
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
        // 回显 handler：把"收到的方法 + 指定请求头 + 请求体"写回响应体，供断言核对入参转换是否正确。
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
    void get_shouldMapAllResultFieldsIntoVO() {
        DevToolHttpSendResponse resp = service.send(request("GET", baseUrl + "/echo"));

        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.getBody().contains("method=GET"));
        assertNotNull(resp.getHeaders());
        assertEquals(List.of("yes"), resp.getHeaders().get("x-echo-resp"));
        assertTrue(resp.getBodyBytes() > 0);
        assertTrue(resp.getDurationMs() >= 0);
        assertFalse(resp.isBodyTruncated());
        assertNull(resp.getError());
    }

    @Test
    void post_shouldConvertHeaderItemsAndBody() {
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
    void blockedTarget_shouldBeTranslatedToBizException() {
        // 默认模式（空白名单）：环回地址被 SSRF 收口拦截，fast fail 抛业务异常
        DevToolHttpService defaultService =
            new DevToolHttpService(new SystemToolHttpGuard(new AdminSystemToolProperties()));

        BizException ex = assertThrows(BizException.class,
            () -> defaultService.send(request("GET", baseUrl + "/echo")));
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, ex.getResultCode());
    }

    @Test
    void nonHttpScheme_shouldFastFail() {
        // 协议校验已并入地址防御点，故错误码与 SSRF 拦截同码（此前是 PARAM_INVALID）
        BizException ex = assertThrows(BizException.class,
            () -> service.send(request("GET", "ftp://127.0.0.1/file")));
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, ex.getResultCode());
    }
}
