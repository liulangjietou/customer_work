package com.richard.fyoung.customerwork.core.model.attribution;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/** 在真实模型订阅边界发布部署/价格快照的透明装饰器。 */
public final class AttributedModel implements Model {

    private final Model delegate;
    private final ModelCallAttribution attribution;

    public AttributedModel(Model delegate, ModelCallAttribution attribution) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.attribution = Objects.requireNonNull(attribution, "attribution");
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(context -> {
            ModelCallAttributionContext.publish(context, attribution);
            return delegate.stream(messages, tools, options);
        });
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
