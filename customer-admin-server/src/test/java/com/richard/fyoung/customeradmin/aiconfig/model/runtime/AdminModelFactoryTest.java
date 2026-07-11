package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminModelFactory} 连通性测试的成功/失败/超时三种场景。
 * 用 JDK 内置 {@link HttpServer} 模拟 OpenAI 兼容端点，不引入 WireMock 等三方测试依赖，
 * 与 {@link AdminModelFactory} 本身"零额外依赖"的设计取舍保持一致。
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

    @Test
    void testConnectivity_shouldSucceed_whenServerReturnsValidChoices() throws Exception {
        server = startServer("/chat/completions", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity(baseUrl(), "sk-test", "gpt-4o-mini");

        assertEquals(ModelTestResult.STATUS_SUCCESS, result.testStatus());
    }

    @Test
    void testConnectivity_shouldFail_whenResponseMissingChoicesArray() throws Exception {
        server = startServer("/chat/completions", exchange -> {
            byte[] body = "{\"error\":\"no choices field\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity(baseUrl(), "sk-test", "gpt-4o-mini");

        assertEquals(ModelTestResult.STATUS_FAILED, result.testStatus());
    }

    @Test
    void testConnectivity_shouldFail_whenServerReturnsHttpError() throws Exception {
        server = startServer("/chat/completions", exchange -> {
            byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity(baseUrl(), "sk-test", "gpt-4o-mini");

        assertEquals(ModelTestResult.STATUS_FAILED, result.testStatus());
        assertTrue(result.message().contains("401"));
    }

    @Test
    void testConnectivity_shouldFail_whenServerHangsPastTimeout() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        server = startServer("/chat/completions", exchange -> {
            requestReceived.countDown();
            try {
                // 故意远超测试用超时（1s），验证硬性截断生效
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(1));
        ModelTestResult result = factory.testConnectivity(baseUrl(), "sk-test", "gpt-4o-mini");

        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        assertEquals(ModelTestResult.STATUS_FAILED, result.testStatus());
        assertTrue(result.message().contains("超时"));
    }

    @Test
    void buildModel_shouldSucceed_forOpenAiProvider() {
        AdminModelFactory factory = new AdminModelFactory();

        Model model = factory.buildModel("openai", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini");

        assertNotNull(model);
    }

    @Test
    void buildModel_shouldReject_nonOpenAiProvider() {
        AdminModelFactory factory = new AdminModelFactory();

        assertThrows(BizException.class, () -> factory.buildModel("anthropic", "https://api.anthropic.com", "sk-test", "claude"));
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
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
