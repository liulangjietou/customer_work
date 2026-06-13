package com.example.customerwork.controller;

import com.example.customerwork.dto.ChatRequest;
import com.example.customerwork.service.CustomerServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 */
@WebFluxTest(CustomerServiceController.class)
class CustomerServiceControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private CustomerServiceService service;

    @Test
    void chat_shouldReturnReply() {
        when(service.chat(anyString(), eq("你好"))).thenReturn(Mono.just("您好，有什么可以帮您？"));

        webTestClient.post().uri("/api/customer/chat")
            .bodyValue(new ChatRequest("u1", "你好"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionId").isEqualTo("u1")
            .jsonPath("$.reply").isEqualTo("您好，有什么可以帮您？");
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
        when(service.chat(anyString(), anyString())).thenReturn(Mono.just("hi"));

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
        when(service.chatStream(anyString(), anyString()))
            .thenReturn(Flux.just("您", "好"));

        webTestClient.post().uri("/api/customer/chat/stream")
            .bodyValue(new ChatRequest("u1", "你好"))
            .exchange()
            .expectStatus().isOk()
            .returnResult(String.class)
            .getResponseBody()
            .blockLast();
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
