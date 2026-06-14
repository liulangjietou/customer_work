package com.richard.fyoung.customerwork.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型重试包装单测：瞬时失败后退避重试并最终成功；模型名透传。
 * @author owlzhangfq@gmail.com
 */
class ResilientChatModelTest {

    @Test
    void stream_shouldRetryAndSucceed() {
        Model delegate = mock(Model.class);
        ChatResponse resp = mock(ChatResponse.class);
        when(delegate.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("transient")))
            .thenReturn(Flux.just(resp));

        ResilientChatModel model = new ResilientChatModel(delegate, 3, 10);

        StepVerifier.create(model.stream(List.<Msg>of(), List.<ToolSchema>of(),
                GenerateOptions.builder().build()))
            .expectNext(resp)
            .verifyComplete();
    }

    @Test
    void getModelName_shouldDelegate() {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("qwen-max");
        assertEquals("qwen-max", new ResilientChatModel(delegate, 2, 1).getModelName());
    }
}
