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

    // ==================== 上下文窗口注入 ====================

    /**
     * 钉住本次修复的前提事实：框架按模型名前缀查硬编码表推断窗口，表里只有各厂商官方模型名。
     * glm / deepseek 这类走 OpenAI 兼容协议接入的第三方模型推断为 0——0 是「未知」不是「窗口为零」。
     * 框架哪天扩了推断表，这条会红，那是提醒而不是故障。
     */
    @Test
    void buildModel_withoutDeclaredWindow_leavesThirdPartyModelWindowUnknown() {
        Model model = publicEndpointFactory().buildModel(
            "openai", "https://api.openai.com/v1", "sk-test", "glm-5.2");

        assertEquals(0, model.getContextWindowSize());
    }

    /**
     * 按真实厂商登记时，窗口能从框架的厂商表推断出来（AgentScope 2.0.0 之后补上了
     * GLM / DeepSeek / Kimi / MiniMax 四张表，此前它们一律推断为 0）。
     *
     * <p>与上一条的差别只有 provider 一个字段：同一个 {@code glm-5.2}，
     * 登记成 OpenAI 兼容端点时框架查 OPENAI 表得 0，登记成 glm 时查 GLM 表得到真实窗口。
     * 两条一起看才说明白「0 是怎么来的」——那是登记方式决定的，不是能力缺失。</p>
     */
    @Test
    void buildModel_withoutDeclaredWindow_infersWindowForNativelyRegisteredProvider() {
        Model model = publicEndpointFactory().buildModel(
            "glm", "https://open.bigmodel.cn/api/paas/v4", "sk-test", "glm-5.2");

        assertTrue(model.getContextWindowSize() > 0,
            "按 glm 厂商登记的 glm-5.2 应能推断出窗口，实际 " + model.getContextWindowSize()
                + "——框架的 GLM 窗口表要么没被查到，要么已不再收录这个模型名");
    }

    /**
     * 本地部署没有窗口推断表，仍然交回框架（表现为 0）。
     *
     * <p>Ollama 的模型名由部署者自己起，任何硬编码清单都猜不中；
     * 这类部署的窗口只能靠运营在资产里登记。</p>
     */
    @Test
    void buildModel_localDeploymentHasNoInferenceTable() {
        Model model = publicEndpointFactory().buildModel(
            "ollama", "http://localhost:11434", "", "qwen3-8b-my-finetune");

        assertEquals(0, model.getContextWindowSize());
    }

    /** 运营登记的声明值永远优先于厂商推断。 */
    @Test
    void declaredWindowWinsOverProviderInference() {
        Model model = publicEndpointFactory().buildModelWithWindow(
            "glm", "https://open.bigmodel.cn/api/paas/v4", "sk-test", "glm-5.2", 12_345);

        assertEquals(12_345, model.getContextWindowSize());
    }

    @Test
    void buildModelWithWindow_shouldOverrideFrameworkInference() {
        Model model = publicEndpointFactory().buildModelWithWindow(
            "openai", "https://api.openai.com/v1", "sk-test", "glm-5.2", 1_000_000);

        assertEquals(1_000_000, model.getContextWindowSize());
    }

    /** 声明缺失或非法时回落框架推断，不能把 0 / 负数当窗口写进运行时。 */
    @Test
    void buildModelWithWindow_shouldFallBackToInferenceOnInvalidDeclaration() {
        AdminModelFactory factory = publicEndpointFactory();

        assertEquals(128_000, factory.buildModelWithWindow(
            "openai", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini", null)
            .getContextWindowSize());
        assertEquals(128_000, factory.buildModelWithWindow(
            "openai", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini", 0)
            .getContextWindowSize());
    }

    /** 依赖未装配（单测与旧构造）时解析返回 null，不能因此抛异常阻断建模。 */
    @Test
    void resolveDeclaredContextWindow_withoutDependencies_shouldReturnNull() {
        assertNull(publicEndpointFactory().resolveDeclaredContextWindow(1L));
        assertNull(publicEndpointFactory().resolveDeclaredContextWindow(null));
    }

    private AdminModelFactory publicEndpointFactory() {
        ModelEndpointPolicy endpointPolicy = new ModelEndpointPolicy(List::of,
            host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        return new AdminModelFactory(mock(ChatModelProber.class), endpointPolicy);
    }
}
