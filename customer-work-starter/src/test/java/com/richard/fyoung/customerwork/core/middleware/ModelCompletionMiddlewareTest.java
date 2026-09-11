package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.middleware.ModelCallInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 记录结束原因不能更改响应内容、既有元数据、参数或结构化输出能力。 */
class ModelCompletionMiddlewareTest {
    private final Model delegate = mock(Model.class);
    private final List<Msg> messages = List.of(Msg.builder().textContent("输入").build());
    private final List<ToolSchema> tools = List.of();
    private final GenerateOptions options = GenerateOptions.builder().build();

    @Test
    void shouldRecordExplicitFinishAndPreserveAllOtherFields() {
        ChatResponse response = new ChatResponse("reply-1", List.of(), null, Map.of("deployment", "primary"), "stop");
        when(delegate.stream(messages, tools, options)).thenReturn(Flux.just(response));
        var recorded = wrapped().stream(messages, tools, options).blockLast();
        assertEquals("stop", recorded.getMetadata().get(ModelCompletionMiddleware.FINISH_REASON_KEY));
        assertEquals("primary", recorded.getMetadata().get("deployment"));
        assertFalse(response.getMetadata().containsKey(ModelCompletionMiddleware.FINISH_REASON_KEY), "不得修改原始响应对象");
        assertSame(response.getContent(), recorded.getContent());
        assertEquals(response.getId(), recorded.getId());
        assertEquals(response.getFinishReason(), recorded.getFinishReason());
        verify(delegate).stream(messages, tools, options);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldNotInventCompletionForResponseWithoutReason(String reason) {
        var response = new ChatResponse("reply-1", List.of(), null, Map.of(), reason);
        when(delegate.stream(messages, tools, options)).thenReturn(Flux.just(response));
        assertSame(response, wrapped().stream(messages, tools, options).blockLast());
    }

    @Test
    void shouldPreserveModelCapabilities() {
        when(delegate.getModelName()).thenReturn("original");
        when(delegate.getContextWindowSize()).thenReturn(32_768);
        when(delegate.supportsNativeStructuredOutput()).thenReturn(true);
        when(delegate.supportsNativeStructuredOutputWithTools()).thenReturn(true);
        Model model = wrapped();
        assertEquals("original", model.getModelName());
        assertEquals(32_768, model.getContextWindowSize());
        assertTrue(model.supportsNativeStructuredOutput());
        assertTrue(model.supportsNativeStructuredOutputWithTools());
    }

    private Model wrapped() {
        AtomicReference<ModelCallInput> received = new AtomicReference<>();
        new ModelCompletionMiddleware().onModelCall(null, null, new ModelCallInput(messages, tools, options, delegate), input -> {
            received.set(input);
            return Flux.empty();
        }).blockLast();
        assertSame(messages, received.get().messages());
        assertSame(tools, received.get().tools());
        assertSame(options, received.get().options());
        return received.get().model();
    }
}
