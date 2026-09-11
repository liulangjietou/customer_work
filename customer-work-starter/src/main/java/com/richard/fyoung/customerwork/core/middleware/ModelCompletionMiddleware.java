package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

/**
 * 在模型协议边界记录厂商明确返回的结束原因，供历史与终态核对。
 * 不覆盖框架的 GenerateReason，也不根据连接关闭、文本或默认 getter 推测原因。
 */
@Component
public class ModelCompletionMiddleware implements MiddlewareBase {
    public static final String FINISH_REASON_KEY = "model.finishReason";

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(),
            new RecordingModel(input.model())));
    }

    @Override
    public int order() {
        return MiddlewareOrders.MODEL_COMPLETION;
    }

    /** 只附加已有的协议事实，模型能力与调用参数全部委托，避免影响结构化输出与上下文预算。 */
    private record RecordingModel(Model delegate) implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return delegate.stream(messages, tools, options).map(response -> {
                String reason = response.getFinishReason();
                if (reason == null || reason.isBlank()) {
                    return response;
                }
                var metadata = response.getMetadata() == null
                    ? new HashMap<String, Object>() : new HashMap<>(response.getMetadata());
                metadata.put(FINISH_REASON_KEY, reason);
                return new ChatResponse(response.getId(), response.getContent(), response.getUsage(),
                    metadata, reason);
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
}
