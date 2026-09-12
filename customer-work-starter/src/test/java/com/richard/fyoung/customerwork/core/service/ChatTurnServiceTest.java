package com.richard.fyoung.customerwork.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.dto.TaskPlanItem;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.observability.MdcContextLifter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 统一对话用例编排的持久化顺序与协议结果测试。 */
class ChatTurnServiceTest {

    @Test
    void terminal_shouldUseTheSameFrozenEvidenceAsTheStoredMessage() {
        var capture = new ChatTerminalCapture();
        var before = new TaskPlanItem("核对退款", "pending", "high");
        var late = new TaskPlanItem("迟到的其他步骤", "completed", "low");
        capture.acceptTaskPlan(List.of(before));
        var chatLog = mock(ChatLogService.class);
        when(chatLog.appendAnswer(ArgumentMatchers.eq("s1"), ArgumentMatchers.isNull(),
            ArgumentMatchers.eq("答复"), ArgumentMatchers.any())).thenAnswer(invocation -> {
                var saved = ChatMessage.of("MSG-frozen", "s1", null,
                    TicketActorType.BOT, null, "答复")
                    .withAnswerEvidence(invocation.getArgument(3)).withId(1);
                capture.acceptTaskPlan(List.of(late));
                return saved;
            });
        var completed = new ChatTurnFinalizer(chatLog).complete("s1", null, "答复", capture, "trace-frozen").block();
        assertThat(completed.terminal().taskPlan()).isEqualTo(completed.message().taskPlan());
        assertThat(completed.terminal().taskPlan()).containsExactly(before);
    }

    @Test
    void completedAnswer_shouldKeepSourcesAndTaskProgressInHistory() {
        var citation = new KnowledgeCitation(
            "售后政策", "refund-policy", "chunk-17", 0.91);
        var task = new TaskPlanItem("核对退款进度", "in_progress", "high");
        CustomerServiceService customerService = mock(CustomerServiceService.class);
        when(customerService.chatStream("s-evidence", "退款进度")).thenReturn(Flux.deferContextual(context -> {
            ChatTerminalCapture capture = ChatTerminalCaptureContext.get(context);
            capture.acceptCitations(List.of(citation));
            capture.acceptTaskPlan(List.of(task));
            return Flux.just("退款尚在处理中");
        }));
        var store = new InMemoryChatMessageStore();
        var service = new ChatTurnService(customerService, new ChatTurnFinalizer(new ChatLogService(store)));
        var completed = service.stream("s-evidence", "退款进度", "TK-evidence")
            .ofType(ChatTurnEvent.Completed.class).blockLast(Duration.ofSeconds(10)).completion();
        ChatMessage history = store.findByMessageId(completed.message().messageId()).orElseThrow();
        JsonNode json = new ObjectMapper()
            .valueToTree(history);
        assertThat(json.path("citations").path(0).path("documentId").asText()).isEqualTo("refund-policy");
        assertThat(json.path("taskPlan").path(0).path("status").asText()).isEqualTo("in_progress");
        assertThat(json.path("finishReason").asText()).isEqualTo(completed.terminal().finishReason());
        assertThat(json.has("traceId")).isFalse();
    }

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
        when(chatLog.appendAnswer(ArgumentMatchers.eq("s1"), ArgumentMatchers.isNull(),
            ArgumentMatchers.eq("ok"), ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("db unavailable"));
        ChatTurnService service = new ChatTurnService(customerService, new ChatTurnFinalizer(chatLog));

        StepVerifier.create(service.stream("s1", "hello", null))
            .expectNextMatches(ChatTurnEvent.Delta.class::isInstance)
            .expectErrorMessage("db unavailable")
            .verify();
    }
}
