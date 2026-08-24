package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.dto.ChatRequest;
import com.richard.fyoung.customerwork.core.dto.ChatResponse;
import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import com.richard.fyoung.customerwork.core.dto.IntentResult;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.service.ChatTurnCompletion;
import com.richard.fyoung.customerwork.core.service.ChatTurnEvent;
import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 控制器 Web 层切片测试：用 WebTestClient 驱动 HTTP 行为，
 * Service 被 mock，不触达模型与 Spring 全量上下文（无需 API Key）。
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(CustomerServiceController.class)
@Import(CustomerWorkProperties.class)   // 提供安全过滤器所需配置（默认鉴权/限流关闭，放行）
class CustomerServiceControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private CustomerServiceService service;

    @MockBean
    private ChatTurnService chatTurnService;

    @MockBean
    private com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator multiAgentOrchestrator;

    @MockBean
    private com.richard.fyoung.customerwork.core.agent.AguiService aguiService;

    @Test
    void chat_shouldReturnReply() {
        when(chatTurnService.chat(anyString(), eq("你好"))).thenReturn(Mono.just(
            new ChatResponse("u1", "您好，有什么可以帮您？", "MSG-9", "MODEL_STOP",
                new ChatUsageSnapshot(10, 4, 0, 14, 0.2), "trace-9")));

        webTestClient.post().uri("/api/customer/chat")
            .bodyValue(new ChatRequest("u1", "你好"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionId").isEqualTo("u1")
            .jsonPath("$.reply").isEqualTo("您好，有什么可以帮您？")
            .jsonPath("$.messageId").isEqualTo("MSG-9")
            .jsonPath("$.finishReason").isEqualTo("MODEL_STOP")
            .jsonPath("$.usage.totalTokens").isEqualTo(14)
            .jsonPath("$.traceId").isEqualTo("trace-9");
    }

    @Test
    void chat_shouldReturn400_whenMessageBlank() {
        webTestClient.post().uri("/api/customer/chat")
            .bodyValue(new ChatRequest("u1", "   "))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void chat_shouldUseAnonymousSession_whenSessionIdMissing() {
        when(chatTurnService.chat(anyString(), anyString())).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            return Mono.just(new ChatResponse(sessionId, "hi", "MSG-1", "CACHE_HIT",
                ChatUsageSnapshot.empty(), "trace-1"));
        });

        webTestClient.post().uri("/api/customer/chat")
            .bodyValue(Map.of("message", "你好"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionId").value(id -> {
                org.assertj.core.api.Assertions.assertThat((String) id).startsWith("anonymous-");
            });
    }

    @Test
    void chatStream_shouldStreamChunks() {
        ChatTerminalEnvelope terminal = new ChatTerminalEnvelope("MSG-9", "MODEL_STOP",
            new ChatUsageSnapshot(10, 4, 0, 14, 0.2), "trace-9");
        ChatMessage message = ChatMessage.of("MSG-9", "u1", null,
            TicketActorType.BOT, null, "您好");
        when(chatTurnService.stream(anyString(), anyString(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(Flux.just(new ChatTurnEvent.Delta("您"), new ChatTurnEvent.Delta("好"),
                new ChatTurnEvent.Completed(new ChatTurnCompletion(message, terminal))));

        webTestClient.post().uri("/api/customer/chat/stream")
            .bodyValue(new ChatRequest("u1", "你好"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:message", "event:done", "MSG-9", "MODEL_STOP", "trace-9"));
    }

    @Test
    void classifyIntent_shouldReturnStructuredResult() {
        when(service.classifyIntent(anyString(), anyString()))
            .thenReturn(Mono.just(new IntentResult("refund", "20260613001", true, "用户要求退款")));

        webTestClient.post().uri("/api/customer/intent")
            .bodyValue(new ChatRequest("u1", "这个订单我要退款"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.intent").isEqualTo("refund")
            .jsonPath("$.orderId").isEqualTo("20260613001")
            .jsonPath("$.urgent").isEqualTo(true);
    }

    @Test
    void interrupt_shouldReturnConfirmation() {
        when(service.interrupt("u1")).thenReturn(true);

        webTestClient.post().uri("/api/customer/session/u1/interrupt")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body ->
                org.assertj.core.api.Assertions.assertThat(body).contains("已发出中断"));
    }

    @Test
    void health_shouldReturnOk() {
        webTestClient.get().uri("/api/customer/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("OK");
    }

    @Test
    void endSession_shouldReturnConfirmation() {
        webTestClient.delete().uri("/api/customer/session/u1")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body ->
                org.assertj.core.api.Assertions.assertThat(body).contains("已结束"));
    }
}
