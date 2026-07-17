package com.richard.fyoung.customerwork.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型层进阶单测：多厂商模型构建（离线）与私有化兜底 FallbackChatModel 切换。
 * @author owlzhangfq@gmail.com
 */
class ModelConfigTest {

    private final ModelConfig modelConfig = new ModelConfig();

    private CustomerWorkProperties.Model modelCfg(String provider) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getModel().setProvider(provider);
        props.getModel().setApiKey("sk-test");
        return props.getModel();
    }

    @Test
    void buildByProvider_shouldBuildBundledVendors() {
        // DashScope 与 OpenAI 的依赖随聚合包提供，可离线构建；
        // anthropic/gemini/ollama 需各自厂商 SDK（运行时按需引入），此处不实例化。
        CustomerWorkProperties.Model cfg = modelCfg("dashscope");
        assertInstanceOf(DashScopeChatModel.class,
            modelConfig.buildByProvider("dashscope", "qwen-max", "sk", "", cfg));
        assertInstanceOf(OpenAIChatModel.class,
            modelConfig.buildByProvider("openai", "gpt-4o", "sk", "", cfg));
    }

    @Test
    void fallback_shouldSwitchToSecondary_onPrimaryError() {
        Model primary = mock(Model.class);
        Model secondary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        when(secondary.getModelName()).thenReturn("secondary");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("primary down")));
        ChatResponse resp = mock(ChatResponse.class);
        when(secondary.stream(any(), any(), any())).thenReturn(Flux.just(resp));

        FallbackChatModel fallback = new FallbackChatModel(primary, secondary);

        StepVerifier.create(fallback.stream(List.<Msg>of(), List.<ToolSchema>of(),
                GenerateOptions.builder().build()))
            .expectNext(resp)
            .verifyComplete();

        assertTrue(fallback.getModelName().contains("fallback:secondary"));
    }
}
