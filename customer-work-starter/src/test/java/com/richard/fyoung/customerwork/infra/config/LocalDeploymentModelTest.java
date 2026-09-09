package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地私有化部署（Ollama）的建模。
 *
 * <h3>守的是什么</h3>
 * <p>这一分支此前<b>把生成参数整个丢掉了</b>：Ollama 用的是自己的 {@code OllamaOptions}
 * 而不是通用 {@code GenerateOptions}，而工厂既没做映射也没传 {@code stream}。
 * 后果是温度、maxTokens、topP 配了等于没配——{@code DynamicOptionsMiddleware} 的
 * 「精确模式」（投诉/退款类问题把温度压到 0.1）在私有化部署上完全无效。
 * 它不报任何错，只表现为"模型好像没按设置来"，而那种偏差很难联想到建模代码。</p>
 *
 * <p>框架其实早就提供了 {@link OllamaOptions#fromGenerateOptions} 这个映射，项目只是一直没调——
 * 与「工具超时重试」是同一形状：能力在框架里，项目从没配过。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class LocalDeploymentModelTest {

    private Model build(GenerateOptions options, boolean stream) {
        return ChatModelFactory.build(ModelProviders.OLLAMA, "qwen2.5", null,
            "http://localhost:11434", stream, options, null, null, null);
    }

    @Test
    @DisplayName("走 Ollama 原生实现，不落兜底分支")
    void usesOllamaModel() {
        assertInstanceOf(OllamaChatModel.class, build(GenerateOptions.builder().build(), false),
            "ollama 应走本地私有化实现，落到 dashscope 兜底意味着请求发去了公网");
    }

    /**
     * 生成参数必须真的传进去。
     *
     * <p>反射读私有字段是刻意的：{@code OllamaChatModel} 没有暴露读取 options 的方法，
     * 而「参数有没有传到」正是这次改动的全部内容——只断言"构建成功"照不出它，
     * 那恰恰是修复前的状态（构建一直是成功的）。</p>
     */
    @Test
    @DisplayName("温度与 maxTokens 真的传进了 Ollama 模型")
    void generationOptionsArePassedThrough() throws Exception {
        GenerateOptions options = GenerateOptions.builder()
            .temperature(0.1)
            .maxTokens(512)
            .build();

        Model model = build(options, false);
        OllamaOptions applied = readDefaultOptions(model);
        assertNotNull(applied, "连 defaultOptions 字段都没找到——反射逻辑失效了，这条测试此刻什么也没在守");

        // 不能只断言 applied 非空：框架 Builder 自带一份默认 OllamaOptions，
        // 参数被丢掉时它照样非空，只是里面的值全是 null（变异测试实证）
        GenerateOptions roundTrip = applied.toGenerateOptions();
        assertEquals(Double.valueOf(0.1), roundTrip.getTemperature(),
            "温度没传进 Ollama 模型——配置里配了多少都不起作用，而且不报任何错");
        assertEquals(Integer.valueOf(512), roundTrip.getMaxTokens(),
            "maxTokens 没传进 Ollama 模型");
    }

    @Test
    @DisplayName("没有生成参数时不设 options，建模也不应失败")
    void nullOptionsIsTolerated() {
        assertInstanceOf(OllamaChatModel.class, build(null, false));
    }

    @Test
    @DisplayName("本地部署不需要凭据")
    void needsNoApiKey() {
        assertTrue(!ModelProviders.requiresApiKey(ModelProviders.OLLAMA));
        assertInstanceOf(OllamaChatModel.class, build(GenerateOptions.builder().build(), true),
            "apiKey 传 null 也应当能建出模型");
    }

    private OllamaOptions readDefaultOptions(Model model) throws Exception {
        for (Field field : model.getClass().getDeclaredFields()) {
            if (OllamaOptions.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return (OllamaOptions) field.get(model);
            }
        }
        return null;
    }
}
