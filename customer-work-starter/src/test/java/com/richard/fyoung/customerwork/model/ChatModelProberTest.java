package com.richard.fyoung.customerwork.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatModelProber} 四厂商最小探活协议单测（由 customer-admin-server 的 AdminModelFactoryTest 平移）：
 * 命中端点/鉴权头 + 响应结构校验 + HTTP 错误 / 超时 / 未知 provider 兜底。
 *
 * <p>用 JDK 内置 {@link HttpServer} 模拟各厂商端点，不引入 WireMock 等三方测试依赖，与被测类
 * 「零额外 HTTP 客户端依赖」的取舍保持一致（真实外网调用需真 Key，离线不测）。</p>
 * @author owlzhangfq@gmail.com
 */
class ChatModelProberTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== 按厂商分发：成功路径 + 命中端点/鉴权头断言 ====================

    @Test
    void probe_openai_shouldSucceed_andHitChatCompletions() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = startServer(exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        });

        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(5)).probe("openai", baseUrl(), "sk-test", "gpt-4o-mini");

        assertTrue(result.success());
        assertTrue(hitPath.get().endsWith("/chat/completions"));
        assertEquals("Bearer sk-test", auth.get());
    }

    @Test
    void probe_dashscope_shouldSucceed_andHitNativeGenerationEndpoint() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = startServer(exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"output\":{\"choices\":[{\"message\":{\"content\":\"hi\"}}]},\"usage\":{}}");
        });

        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(5)).probe("dashscope", baseUrl(), "sk-ds", "qwen-max");

        assertTrue(result.success());
        assertTrue(hitPath.get().endsWith("/api/v1/services/aigc/text-generation/generation"));
        assertEquals("Bearer sk-ds", auth.get());
    }

    @Test
    void probe_anthropic_shouldSucceed_andCarryApiKeyAndVersionHeaders() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> versionHeader = new AtomicReference<>();
        server = startServer(exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            respond(exchange, 200, "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"role\":\"assistant\"}");
        });

        ChatModelProber.ProbeResult result = new ChatModelProber(Duration.ofSeconds(5))
            .probe("anthropic", baseUrl(), "sk-ant", "claude-3-5-sonnet-latest");

        assertTrue(result.success());
        assertTrue(hitPath.get().endsWith("/v1/messages"));
        assertEquals("sk-ant", apiKeyHeader.get());
        assertEquals("2023-06-01", versionHeader.get());
    }

    @Test
    void probe_gemini_shouldSucceed_andCarryApiKeyInHeaderNotUrl() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        server = startServer(exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            respond(exchange, 200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}");
        });

        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(5)).probe("gemini", baseUrl(), "sk-gm", "gemini-2.0-flash");

        assertTrue(result.success());
        assertTrue(hitPath.get().contains("/v1beta/models/gemini-2.0-flash:generateContent"));
        // key 走 x-goog-api-key 头，URL 里不得出现（避免 URI 异常信息/日志把 key 带出去）
        assertEquals("sk-gm", apiKeyHeader.get());
        assertFalse(hitPath.get().contains("sk-gm"));
    }

    @Test
    void probe_unknownProvider_shouldFallBackToOpenAiCompatibleProtocol() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        server = startServer(exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        });

        // provider 合法性由调用方收口，本类对未知/空 provider 按 OpenAI 兼容协议探活
        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(5)).probe(null, baseUrl(), "sk-test", "any-model");

        assertTrue(result.success());
        assertTrue(hitPath.get().endsWith("/chat/completions"));
    }

    // ==================== 失败 / 超时 / 结构不符 ====================

    @Test
    void probe_shouldFail_whenResponseMissingExpectedStructure() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, "{\"error\":\"no choices field\"}"));

        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(5)).probe("openai", baseUrl(), "sk-test", "gpt-4o-mini");

        assertFalse(result.success());
    }

    @Test
    void probe_shouldFail_whenServerReturnsHttpError() throws Exception {
        server = startServer(exchange -> respond(exchange, 401, "unauthorized"));

        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(5)).probe("anthropic", baseUrl(), "sk-test", "claude");

        assertFalse(result.success());
        assertTrue(result.message().contains("401"));
    }

    @Test
    void probe_shouldFail_whenServerHangsPastTimeout() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        server = startServer(exchange -> {
            requestReceived.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"choices\":[]}");
        });

        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(1)).probe("openai", baseUrl(), "sk-test", "gpt-4o-mini");

        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        assertFalse(result.success());
        assertTrue(result.message().contains("超时"));
    }

    @Test
    void probe_shouldFail_whenEndpointUnreachable() {
        // 未监听的端口：连接异常走通用 catch 分支，返回失败而非抛出
        ChatModelProber.ProbeResult result =
            new ChatModelProber(Duration.ofSeconds(1)).probe("openai", "http://127.0.0.1:1", "sk", "m");

        assertFalse(result.success());
        assertTrue(result.message() != null && !result.message().isEmpty());
    }

    // ==================== HttpServer 脚手架 ====================

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private HttpServer startServer(Handler handler) throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
