package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.core.model.ChatModelProber;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminModelFactory} 薄壳职责单测：未知 provider fast fail（{@link ModelProvider} 收口）、
 * 各 provider 走 starter {@code ChatModelFactory} 建出对应厂商实例、探活结果译成 {@link ModelTestResult}。
 *
 * <p>四厂商最小探活协议本身（端点/鉴权头/响应结构/超时）已随实现下沉，由 starter 的
 * {@code ChatModelProberTest} 覆盖，这里 mock Prober 只验证「委托 + 结果翻译」这段。</p>
 * @author owlzhangfq@gmail.com
 */
class AdminModelFactoryTest {

    // ==================== 探活委托 + 结果翻译 ====================

    @Test
    void testConnectivity_shouldTranslateProbeSuccess() {
        ChatModelProber prober = mock(ChatModelProber.class);
        when(prober.probe("openai", "https://api.example.com/v1", "sk-test", "gpt-4o-mini"))
            .thenReturn(new ChatModelProber.ProbeResult(true, null));

        AdminModelFactory factory = new AdminModelFactory(prober);
        ModelTestResult result = factory.testConnectivity(
            "openai", "https://api.example.com/v1", "sk-test", "gpt-4o-mini");

        assertEquals(ConnectivityTestStatus.SUCCESS, result.testStatus());
        assertNotNull(result.testTime());
        assertNull(result.message());
    }

    @Test
    void testConnectivity_shouldTranslateProbeFailure_withMessage() {
        ChatModelProber prober = mock(ChatModelProber.class);
        when(prober.probe("anthropic", "https://api.example.com", "sk-test", "claude"))
            .thenReturn(new ChatModelProber.ProbeResult(false, "HTTP 401: unauthorized"));

        AdminModelFactory factory = new AdminModelFactory(prober);
        ModelTestResult result = factory.testConnectivity(
            "anthropic", "https://api.example.com", "sk-test", "claude");

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
        Model model = publicEndpointFactory().buildModel(
            "openai", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini");
        assertInstanceOf(OpenAIChatModel.class, model);
    }

    @Test
    void buildModel_shouldBuildDashScopeChatModel() {
        Model model = publicEndpointFactory().buildModel(
            "dashscope", "https://dashscope.aliyuncs.com", "sk-ds", "qwen-max");
        assertInstanceOf(DashScopeChatModel.class, model);
    }

    @Test
    void buildModel_shouldBuildAnthropicChatModel() {
        Model model = publicEndpointFactory().buildModel(
            "anthropic", "https://api.anthropic.com", "sk-ant", "claude-3-5-sonnet-latest");
        assertInstanceOf(AnthropicChatModel.class, model);
    }

    @Test
    void buildModel_shouldBuildGeminiChatModel() {
        Model model = publicEndpointFactory().buildModel(
            "gemini", "https://generativelanguage.googleapis.com", "sk-gm", "gemini-2.0-flash");
        assertInstanceOf(GeminiChatModel.class, model);
    }

    @Test
    void buildModel_shouldRejectPrivateEndpointWithoutAllowlist() {
        ModelEndpointPolicy endpointPolicy = new ModelEndpointPolicy(List::of,
            host -> new InetAddress[] {InetAddress.getByName(host)});
        AdminModelFactory factory = new AdminModelFactory(mock(ChatModelProber.class), endpointPolicy);

        assertThrows(HttpTargetForbiddenException.class,
            () -> factory.buildModel("openai", "http://10.0.0.8/v1", "sk-test", "gpt-4o-mini"));
    }

    @Test
    void buildModel_shouldRejectPublicEndpointMissingFromConfiguredAllowlist() {
        ModelEndpointPolicy endpointPolicy = new ModelEndpointPolicy(
            () -> List.of("model.internal"),
            host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        AdminModelFactory factory = new AdminModelFactory(mock(ChatModelProber.class), endpointPolicy);

        assertThrows(HttpTargetForbiddenException.class,
            () -> factory.buildModel("openai", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini"));
    }

    @Test
    void buildModel_shouldAllowAllowlistedPrivateEndpoint() {
        ModelEndpointPolicy endpointPolicy = new ModelEndpointPolicy(
            () -> List.of("model.internal"),
            host -> new InetAddress[] {InetAddress.getByName("10.20.30.40")});
        AdminModelFactory factory = new AdminModelFactory(mock(ChatModelProber.class), endpointPolicy);

        Model model = factory.buildModel(
            "openai", "https://model.internal/v1/", "sk-test", "gpt-4o-mini");

        assertInstanceOf(OpenAIChatModel.class, model);
    }

    @Test
    void buildModel_shouldFastFail_forUnknownProvider() {
        AdminModelFactory factory = new AdminModelFactory();
        assertThrows(BizException.class,
            () -> factory.buildModel("wenxin", "https://example.com", "sk-test", "ernie"));
    }

    @Test
    void buildModel_isCaseInsensitive_onProvider() {
        Model model = publicEndpointFactory().buildModel(
            "OpenAI", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini");
        assertInstanceOf(OpenAIChatModel.class, model);
    }

    private AdminModelFactory publicEndpointFactory() {
        ModelEndpointPolicy endpointPolicy = new ModelEndpointPolicy(List::of,
            host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        return new AdminModelFactory(mock(ChatModelProber.class), endpointPolicy);
    }
}
