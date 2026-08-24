package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.model.attribution.AttributedModel;
import com.richard.fyoung.customerwork.core.model.failover.FailoverModel;
import com.richard.fyoung.customerwork.infra.config.properties.ModelProperties;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型层进阶单测：多厂商模型构建（离线）+ {@code buildChain} 组装出的模型链语义
 * （主挂切备 / 备也挂抛错 / 首分片后不切备 / 重试叠加 / 未开兜底时仅保留归因装饰）。
 *
 * <p>兜底实现已由 {@code FallbackChatModel} 换成下沉到 starter 的
 * {@link FailoverModel}（主备有序候选 + 熔断记忆），原有语义在此逐条保留。</p>
 * @author owlzhangfq@gmail.com
 */
class ModelConfigTest {

    private final ModelConfig modelConfig = new ModelConfig();

    private ModelProperties modelCfg(String provider) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getModel().setProvider(provider);
        props.getModel().setApiKey("sk-test");
        return props.getModel();
    }

    /** 用桩模型替换真实厂商构建：兜底 provider（ollama）返回备模型，其余返回主模型。 */
    private static final class StubbingModelConfig extends ModelConfig {
        private final Model primary;
        private final Model fallback;

        StubbingModelConfig(Model primary, Model fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        Model buildByProvider(String provider, String name, String apiKey, String baseUrl,
                              ModelProperties cfg) {
            return "ollama".equals(provider) ? fallback : primary;
        }
    }

    private Flux<ChatResponse> stream(Model model) {
        return model.stream(List.<Msg>of(), List.<ToolSchema>of(), GenerateOptions.builder().build());
    }

    @Test
    void buildByProvider_shouldBuildBundledVendors() {
        // DashScope 与 OpenAI 的依赖随聚合包提供，可离线构建；
        // anthropic/gemini/ollama 需各自厂商 SDK（运行时按需引入），此处不实例化。
        ModelProperties cfg = modelCfg("dashscope");
        assertInstanceOf(DashScopeChatModel.class,
            modelConfig.buildByProvider("dashscope", "qwen-max", "sk", "", cfg));
        assertInstanceOf(OpenAIChatModel.class,
            modelConfig.buildByProvider("openai", "gpt-4o", "sk", "", cfg));
    }

    @Test
    void buildChain_shouldAttributeAndDelegatePrimary_whenFallbackAndRetryDisabled() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        ChatResponse response = mock(ChatResponse.class);
        when(primary.stream(any(), any(), any())).thenReturn(Flux.just(response));
        ModelProperties cfg = modelCfg("dashscope");

        Model chain = new StubbingModelConfig(primary, mock(Model.class)).buildChain(cfg);

        // 未开兜底/重试时只保留归因装饰器；模型能力和流式结果必须透明委托。
        assertInstanceOf(AttributedModel.class, chain);
        StepVerifier.create(stream(chain))
            .expectNext(response)
            .verifyComplete();
        verify(primary).stream(any(), any(), any());
    }

    @Test
    void buildChain_shouldSwitchToFallback_onPrimaryError() {
        Model primary = mock(Model.class);
        Model secondary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        when(secondary.getModelName()).thenReturn("secondary");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("primary down")));
        ChatResponse resp = mock(ChatResponse.class);
        when(secondary.stream(any(), any(), any())).thenReturn(Flux.just(resp));

        ModelProperties cfg = modelCfg("dashscope");
        cfg.getFallback().setEnabled(true);
        Model chain = new StubbingModelConfig(primary, secondary).buildChain(cfg);

        assertInstanceOf(FailoverModel.class, chain);
        StepVerifier.create(stream(chain))
            .expectNext(resp)
            .verifyComplete();
    }

    @Test
    void buildChain_shouldPropagateError_whenFallbackAlsoFails() {
        Model primary = mock(Model.class);
        Model secondary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        when(secondary.getModelName()).thenReturn("secondary");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("primary down")));
        when(secondary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("fallback down")));

        ModelProperties cfg = modelCfg("dashscope");
        cfg.getFallback().setEnabled(true);
        Model chain = new StubbingModelConfig(primary, secondary).buildChain(cfg);

        StepVerifier.create(stream(chain))
            .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("fallback down"))
            .verify();
    }

    @Test
    void buildChain_shouldNotSwitchToFallback_whenPrimaryFailsAfterFirstChunk() {
        Model primary = mock(Model.class);
        Model secondary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        when(secondary.getModelName()).thenReturn("secondary");
        ChatResponse first = mock(ChatResponse.class);
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.concat(Flux.just(first), Flux.error(new RuntimeException("mid-stream failure"))));

        ModelProperties cfg = modelCfg("dashscope");
        cfg.getFallback().setEnabled(true);
        Model chain = new StubbingModelConfig(primary, secondary).buildChain(cfg);

        StepVerifier.create(stream(chain))
            .expectNext(first)
            .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("mid-stream"))
            .verify();

        // 首分片已发出后失败，不应再调用兜底模型（否则会拼接错乱）
        verify(secondary, never()).stream(any(), any(), any());
    }

    @Test
    void buildChain_shouldWrapWithRetry_whenRetryEnabled() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");

        ModelProperties cfg = modelCfg("dashscope");
        cfg.getRetry().setEnabled(true);
        Model chain = new StubbingModelConfig(primary, mock(Model.class)).buildChain(cfg);

        // 重试壳在最外层：单次调用先按主备顺序切候选，整链失败后再由重试壳退避重发
        assertInstanceOf(ResilientChatModel.class, chain);
    }
}
