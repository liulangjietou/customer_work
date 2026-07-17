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

    /** P6 修复：4xx 等客户端错误不应重试，避免白白浪费时延与 Token。 */
    @Test
    void stream_shouldNotRetry_onClientError() {
        Model delegate = mock(Model.class);
        when(delegate.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("HTTP 401 Unauthorized: invalid api key")))
            .thenReturn(Flux.just(mock(ChatResponse.class)));

        ResilientChatModel model = new ResilientChatModel(delegate, 3, 10);

        StepVerifier.create(model.stream(List.<Msg>of(), List.<ToolSchema>of(),
                GenerateOptions.builder().build()))
            .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("401"))
            .verify();

        org.mockito.Mockito.verify(delegate, org.mockito.Mockito.times(1))
            .stream(any(), any(), any());
    }

    /** P6 兜底：未识别为客户端错误的瞬时异常仍重试；可识别的客户端错误不重试。 */
    @Test
    void isRetryable_defaultsToRetryForUnknownErrors() {
        org.junit.jupiter.api.Assertions.assertTrue(
            ResilientChatModel.isRetryable(new RuntimeException("connection reset")));
        org.junit.jupiter.api.Assertions.assertFalse(
            ResilientChatModel.isRetryable(new RuntimeException("400 invalid_api_key")));
    }
}
