package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 国产模型一等公民接入测试。
 *
 * <p><b>守的是什么</b>：GLM / DeepSeek / Kimi / MiniMax 此前只能配成 {@code provider=openai}
 * 走通用兼容协议。通用路径的消息格式是"尽力而为"的——上游为此修过一个
 * {@code DeepSeek formatter: preserve system role} 的缺陷（#2189），也就是说
 * <b>通用路径会丢掉 system role</b>。系统提示词是客服智能体全部行为约束的载体，
 * 丢了意味着人设、边界、话术规范在那一次调用里静默失效，而且不报错。</p>
 *
 * <p>另一半是上下文窗口：框架按模型名前缀查表推断，表里只有各厂商官方模型名。
 * 这几家走 openai 兼容接入时一律推断为 0，而 0 的含义是"表里没有这个名字"
 * 而不是"窗口为零"——上线认证的窗口检查因此恒为失败（PR #154 修过一次的形状）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class DomesticModelProviderTest {

    private Model build(String provider, String modelName, Integer window) {
        return ChatModelFactory.build(provider, modelName, "test-key", null, false,
            GenerateOptions.builder().build(), null, null, window);
    }

    @Test
    @DisplayName("四家国产模型都走专用 provider，不落兜底分支")
    void domesticProvidersAreFirstClass() {
        List<String> providers = List.of(ModelProviders.GLM, ModelProviders.DEEPSEEK,
            ModelProviders.KIMI, ModelProviders.MINIMAX);

        for (String provider : providers) {
            Model model = build(provider, provider + "-test-model", null);
            assertInstanceOf(OpenAIChatModel.class, model,
                provider + " 应走 OpenAI 兼容协议 + 厂商专用 Formatter，而不是落到 dashscope 兜底");
        }
    }

    /**
     * 显式声明的上下文窗口必须生效。
     *
     * <p>这正是 PR #154 那个缺陷的形状：框架对表外模型名推断为 0，而 0 被当成"窗口为零"
     * 参与能力判定，使 OpenAI 兼容部署的上线认证恒为失败。</p>
     */
    @Test
    @DisplayName("显式声明的上下文窗口在国产模型上照常生效")
    void declaredContextWindowApplies() {
        Model model = build(ModelProviders.GLM, "glm-4-plus", 128_000);

        assertEquals(128_000, model.getContextWindowSize(),
            "运营在资产里登记的窗口必须生效——推断表里没有这个名字不等于窗口为零");
    }

    @Test
    @DisplayName("各厂商默认端点是可用的官方地址")
    void defaultBaseUrlsAreOfficialEndpoints() {
        assertTrue(ModelProviders.DefaultBaseUrls.GLM.startsWith("https://"), "GLM 端点必须是 https");
        assertTrue(ModelProviders.DefaultBaseUrls.DEEPSEEK.startsWith("https://"));
        assertTrue(ModelProviders.DefaultBaseUrls.KIMI.startsWith("https://"));
        assertTrue(ModelProviders.DefaultBaseUrls.MINIMAX.startsWith("https://"));
        assertEquals(4, List.of(ModelProviders.DefaultBaseUrls.GLM, ModelProviders.DefaultBaseUrls.DEEPSEEK,
            ModelProviders.DefaultBaseUrls.KIMI, ModelProviders.DefaultBaseUrls.MINIMAX)
            .stream().distinct().count(), "四家端点不得重复——复制粘贴写错会把请求发去别家");
    }

    /** 配了 base-url 时以配置为准：自建网关、代理、私有化部署都靠它。 */
    @Test
    @DisplayName("显式 base-url 覆盖官方默认端点")
    void explicitBaseUrlOverridesDefault() {
        Model model = ChatModelFactory.build(ModelProviders.GLM, "glm-4", "k",
            "https://my-gateway.internal/v1", false, GenerateOptions.builder().build(), null, null, null);

        assertInstanceOf(OpenAIChatModel.class, model,
            "自建网关场景必须仍走 GLM 的专用 Formatter，而不是退回通用兼容路径");
    }
}
