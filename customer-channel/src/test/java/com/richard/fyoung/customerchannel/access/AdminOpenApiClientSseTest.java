package com.richard.fyoung.customerchannel.access;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AdminOpenApiClient} 的 SSE 聚合与会话解析测试。
 *
 * <p>用 JDK 内置 {@link HttpServer}（零额外依赖）伪造 admin 开放 API：
 * chat 返回 text/event-stream，验证 message 增量按序拼接、done 结束、error 抛出；
 * resolve 返回 Result 包装，验证取到 sessionId。</p>
 *
 * <p>message/error 事件的 data 为 <b>JSON 字符串字面量</b>（admin 侧 SSE 换行安全契约），
 * 客户端 {@code readValue(String.class)} 解码还原（含换行）。done 仍为固定 {@code [DONE]}。</p>
 * @author owlzhangfq@gmail.com
 */
class AdminOpenApiClientSseTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void serve(String path, String contentType, String body) {
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
    }

    @Test
    void shouldAggregateMessageEventsInOrder() {
        // data 为 JSON 字符串字面量（带引号），客户端解码后拼接
        String sse = ""
            + "event: message\n"
            + "data: \"你好\"\n\n"
            + "event: message\n"
            + "data: \"世界\"\n\n"
            + "event: done\n"
            + "data: [DONE]\n\n";
        serve("/api/open/agents/agentX/chat", "text/event-stream", sse);
        server.start();

        AdminOpenApiClient client = new AdminOpenApiClient(baseUrl, "tok");
        String answer = client.chat("agentX", "s1", "hi", 10);

        assertEquals("你好世界", answer);
    }

    @Test
    void shouldPreserveNewlineFromJsonEncodedData() {
        // JSON 字面量里的 \n 是转义序列（不裸露在 SSE 帧里），解码后还原为真实换行
        String sse = ""
            + "event: message\n"
            + "data: \"第一行\\n\"\n\n"
            + "event: message\n"
            + "data: \"第二行\"\n\n"
            + "event: done\n"
            + "data: [DONE]\n\n";
        serve("/api/open/agents/agentX/chat", "text/event-stream", sse);
        server.start();

        AdminOpenApiClient client = new AdminOpenApiClient(baseUrl, "tok");
        String answer = client.chat("agentX", "s1", "hi", 10);

        assertEquals("第一行\n第二行", answer);
    }

    @Test
    void shouldThrowOnErrorEvent() {
        String sse = ""
            + "event: message\n"
            + "data: \"partial\"\n\n"
            + "event: error\n"
            + "data: \"upstream boom\"\n\n";
        serve("/api/open/agents/agentX/chat", "text/event-stream", sse);
        server.start();

        AdminOpenApiClient client = new AdminOpenApiClient(baseUrl, "tok");

        assertThrows(Exception.class, () -> client.chat("agentX", "s1", "hi", 10));
    }

    @Test
    void shouldResolveSessionId() {
        serve("/api/open/channel/sessions/resolve", "application/json",
            "{\"code\":0,\"message\":\"ok\",\"data\":{\"sessionId\":\"sess-42\"}}");
        server.start();

        AdminOpenApiClient client = new AdminOpenApiClient(baseUrl, "tok");
        String sessionId = client.resolveSession("dingtalk", "ak1", "userA");

        assertEquals("sess-42", sessionId);
    }
}
