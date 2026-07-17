package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminModelFactory} 四厂商连通性探活（按厂商分发）+ 各 provider 模型构建 + 未知 provider fast fail。
 * 用 JDK 内置 {@link HttpServer} 模拟各厂商端点，不引入 WireMock 等三方测试依赖，与 {@link AdminModelFactory}
 * 本身「零额外 HTTP 客户端依赖」的设计取舍保持一致（真实外网调用需真 Key，离线不测）。
 * @author owlzhangfq@gmail.com
 */
class AdminModelFactoryTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== 连通性探活：按厂商分发（成功路径 + 命中端点/鉴权头断言）====================

    @Test
    void testConnectivity_openai_shouldSucceed_andHitChatCompletions() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = startServer("/", exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("openai", baseUrl(), "sk-test", "gpt-4o-mini");

        assertEquals(ModelTestResult.STATUS_SUCCESS, result.testStatus());
        assertTrue(hitPath.get().endsWith("/chat/completions"));
        assertEquals("Bearer sk-test", auth.get());
    }

    @Test
    void testConnectivity_dashscope_shouldSucceed_andHitNativeGenerationEndpoint() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = startServer("/", exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"output\":{\"choices\":[{\"message\":{\"content\":\"hi\"}}]},\"usage\":{}}");
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("dashscope", baseUrl(), "sk-ds", "qwen-max");

        assertEquals(ModelTestResult.STATUS_SUCCESS, result.testStatus());
        assertTrue(hitPath.get().endsWith("/api/v1/services/aigc/text-generation/generation"));
        assertEquals("Bearer sk-ds", auth.get());
    }

    @Test
    void testConnectivity_anthropic_shouldSucceed_andCarryApiKeyAndVersionHeaders() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> versionHeader = new AtomicReference<>();
        server = startServer("/", exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            respond(exchange, 200, "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"role\":\"assistant\"}");
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("anthropic", baseUrl(), "sk-ant", "claude-3-5-sonnet-latest");

        assertEquals(ModelTestResult.STATUS_SUCCESS, result.testStatus());
        assertTrue(hitPath.get().endsWith("/v1/messages"));
        assertEquals("sk-ant", apiKeyHeader.get());
        assertEquals("2023-06-01", versionHeader.get());
    }

    @Test
    void testConnectivity_gemini_shouldSucceed_andCarryApiKeyInHeaderNotUrl() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        server = startServer("/", exchange -> {
            hitPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            respond(exchange, 200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}");
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("gemini", baseUrl(), "sk-gm", "gemini-2.0-flash");

        assertEquals(ModelTestResult.STATUS_SUCCESS, result.testStatus());
        assertTrue(hitPath.get().contains("/v1beta/models/gemini-2.0-flash:generateContent"));
        // key 走 x-goog-api-key 头，URL 里不得出现（避免 URI 异常信息/日志把 key 带出去）
        assertEquals("sk-gm", apiKeyHeader.get());
        assertFalse(hitPath.get().contains("sk-gm"));
    }

    // ==================== 连通性探活：失败 / 超时 / 结构不符 ====================

    @Test
    void testConnectivity_shouldFail_whenResponseMissingExpectedStructure() throws Exception {
        server = startServer("/", exchange -> respond(exchange, 200, "{\"error\":\"no choices field\"}"));

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("openai", baseUrl(), "sk-test", "gpt-4o-mini");

        assertEquals(ModelTestResult.STATUS_FAILED, result.testStatus());
    }

    @Test
    void testConnectivity_shouldFail_whenServerReturnsHttpError() throws Exception {
        server = startServer("/", exchange -> respond(exchange, 401, "unauthorized"));

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("anthropic", baseUrl(), "sk-test", "claude");

        assertEquals(ModelTestResult.STATUS_FAILED, result.testStatus());
        assertTrue(result.message().contains("401"));
    }

    @Test
    void testConnectivity_shouldFail_whenServerHangsPastTimeout() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        server = startServer("/", exchange -> {
            requestReceived.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"choices\":[]}");
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(1));
        ModelTestResult result = factory.testConnectivity("openai", baseUrl(), "sk-test", "gpt-4o-mini");

        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        assertEquals(ModelTestResult.STATUS_FAILED, result.testStatus());
        assertTrue(result.message().contains("超时"));
    }

    @Test
    void testConnectivity_shouldFastFail_forUnknownProvider() {
        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(1));
        assertThrows(BizException.class,
            () -> factory.testConnectivity("unknown", "http://127.0.0.1:1", "sk", "m"));
    }

    // ==================== buildModel：各 provider 分支构建 + 未知 fast fail ====================

    @Test
    void buildModel_shouldBuildOpenAiChatModel() {
        Model model = new AdminModelFactory().buildModel("openai", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini");
        assertInstanceOf(OpenAIChatModel.class, model);
    }

    @Test
    void buildModel_shouldBuildDashScopeChatModel() {
        Model model = new AdminModelFactory().buildModel("dashscope", "https://dashscope.aliyuncs.com", "sk-ds", "qwen-max");
        assertInstanceOf(DashScopeChatModel.class, model);
    }

    @Test
    void buildModel_shouldBuildAnthropicChatModel() {
        Model model = new AdminModelFactory().buildModel("anthropic", "https://api.anthropic.com", "sk-ant", "claude-3-5-sonnet-latest");
        assertInstanceOf(AnthropicChatModel.class, model);
    }

    @Test
    void buildModel_shouldBuildGeminiChatModel() {
        Model model = new AdminModelFactory().buildModel("gemini", "https://generativelanguage.googleapis.com", "sk-gm", "gemini-2.0-flash");
        assertInstanceOf(GeminiChatModel.class, model);
    }

    @Test
    void buildModel_shouldFastFail_forUnknownProvider() {
        AdminModelFactory factory = new AdminModelFactory();
        assertThrows(BizException.class,
            () -> factory.buildModel("wenxin", "https://example.com", "sk-test", "ernie"));
    }

    @Test
    void buildModel_isCaseInsensitive_onProvider() {
        Model model = new AdminModelFactory().buildModel("OpenAI", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini");
        assertNotNull(model);
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

    private HttpServer startServer(String path, Handler handler) throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext(path, exchange -> {
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
