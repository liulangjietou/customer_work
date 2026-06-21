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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 修复验证：流式兜底只在「首个分片到达前」失败才切换，
 * 已经吐过分片再失败则不切兜底，避免主模型前半段与兜底完整输出拼接错乱。
 * @author owlzhangfq@gmail.com
 */
class FallbackChatModelTest {

    @Test
    void shouldFallback_whenPrimaryFailsBeforeFirstChunk() {
        Model primary = mock(Model.class);
        Model secondary = mock(Model.class);
        when(primary.getModelName()).thenReturn("p");
        when(secondary.getModelName()).thenReturn("s");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("primary down before first chunk")));
        ChatResponse resp = mock(ChatResponse.class);
        when(secondary.stream(any(), any(), any())).thenReturn(Flux.just(resp));

        FallbackChatModel model = new FallbackChatModel(primary, secondary);

        StepVerifier.create(model.stream(List.<Msg>of(), List.<ToolSchema>of(),
                GenerateOptions.builder().build()))
            .expectNext(resp)
            .verifyComplete();
    }

    @Test
    void shouldNotFallback_whenPrimaryFailsAfterFirstChunk() {
        Model primary = mock(Model.class);
        Model secondary = mock(Model.class);
        when(primary.getModelName()).thenReturn("p");
        when(secondary.getModelName()).thenReturn("s");
        ChatResponse first = mock(ChatResponse.class);
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.concat(Flux.just(first),
                Flux.error(new RuntimeException("mid-stream failure"))));
        when(secondary.stream(any(), any(), any())).thenReturn(Flux.just(mock(ChatResponse.class)));

        FallbackChatModel model = new FallbackChatModel(primary, secondary);

        StepVerifier.create(model.stream(List.<Msg>of(), List.<ToolSchema>of(),
                GenerateOptions.builder().build()))
            .expectNext(first)
            .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("mid-stream"))
            .verify();

        // 首分片已发出后失败，不应再调用兜底模型（否则会拼接错乱）
        verify(secondary, never()).stream(any(), any(), any());
    }
}
