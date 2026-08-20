package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminModelFactory} 薄壳职责单测：未知 provider fast fail（{@link ModelProvider} 收口）、
 * 各 provider 走 starter {@code ChatModelFactory} 建出对应厂商实例、探活结果译成 {@link ModelTestResult}。
 *
 * <p>四厂商最小探活协议本身（端点/鉴权头/响应结构/超时）已随实现下沉，由 starter 的
 * {@code ChatModelProberTest} 覆盖，这里只用一个 JDK {@link HttpServer} 验证「委托 + 结果翻译」这段。</p>
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

    // ==================== 探活委托 + 结果翻译 ====================

    @Test
    void testConnectivity_shouldTranslateProbeSuccess() throws Exception {
        server = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("openai", baseUrl(), "sk-test", "gpt-4o-mini");

        assertEquals(ConnectivityTestStatus.SUCCESS, result.testStatus());
        assertNotNull(result.testTime());
        assertNull(result.message());
    }

    @Test
    void testConnectivity_shouldTranslateProbeFailure_withMessage() throws Exception {
        server = startServer(401, "unauthorized");

        AdminModelFactory factory = new AdminModelFactory(Duration.ofSeconds(5));
        ModelTestResult result = factory.testConnectivity("anthropic", baseUrl(), "sk-test", "claude");

        assertEquals(ConnectivityTestStatus.FAILED, result.testStatus());
        assertNotNull(result.testTime());
        assertTrue(result.message().contains("401"));
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
        assertInstanceOf(OpenAIChatModel.class, model);
    }

    // ==================== HttpServer 脚手架 ====================

    private HttpServer startServer(int status, String body) throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        httpServer.start();
        return httpServer;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
