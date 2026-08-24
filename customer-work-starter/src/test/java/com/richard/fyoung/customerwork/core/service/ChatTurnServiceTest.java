package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.observability.MdcContextLifter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 统一对话用例编排的持久化顺序与协议结果测试。 */
class ChatTurnServiceTest {

    @Test
    void stream_shouldPersistBeforeEmittingTerminalAndKeepTraceId() {
        CustomerServiceService customerService = mock(CustomerServiceService.class);
        when(customerService.chatStream("s1", "hello")).thenReturn(Flux.just("你", "好"));
        InMemoryChatMessageStore store = new InMemoryChatMessageStore();
        ChatTurnService service = new ChatTurnService(customerService,
            new ChatTurnFinalizer(new ChatLogService(store)));
        AtomicReference<ChatTurnCompletion> completed = new AtomicReference<>();

        StepVerifier.create(service.stream("s1", "hello", "TK-1")
                .contextWrite(context -> context.put(MdcContextLifter.TRACE_ID_KEY, "trace-1")))
            .assertNext(event -> assertThat(((ChatTurnEvent.Delta) event).content()).isEqualTo("你"))
            .assertNext(event -> assertThat(((ChatTurnEvent.Delta) event).content()).isEqualTo("好"))
            .assertNext(event -> completed.set(((ChatTurnEvent.Completed) event).completion()))
            .verifyComplete();

        ChatTurnCompletion result = completed.get();
        assertThat(result.terminal().messageId()).isEqualTo(result.message().messageId());
        assertThat(result.terminal().traceId()).isEqualTo("trace-1");
        assertThat(result.terminal().finishReason()).isEqualTo("CACHE_HIT");
        assertThat(store.findByMessageId(result.terminal().messageId()))
            .map(ChatMessage::content).contains("你好");
    }

    @Test
    void stream_shouldNotEmitFakeTerminalWhenPersistenceFails() {
        CustomerServiceService customerService = mock(CustomerServiceService.class);
        when(customerService.chatStream("s1", "hello")).thenReturn(Flux.just("ok"));
        ChatLogService chatLog = mock(ChatLogService.class);
        when(chatLog.append("s1", null, com.richard.fyoung.customerwork.data.ticket.TicketActorType.BOT,
            null, "ok")).thenThrow(new IllegalStateException("db unavailable"));
        ChatTurnService service = new ChatTurnService(customerService, new ChatTurnFinalizer(chatLog));

        StepVerifier.create(service.stream("s1", "hello", null))
            .expectNextMatches(ChatTurnEvent.Delta.class::isInstance)
            .expectErrorMessage("db unavailable")
            .verify();
    }
}
