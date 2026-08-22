package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.feedback.FeedbackService;
import com.richard.fyoung.customerwork.capability.feedback.FeedbackType;
import com.richard.fyoung.customerwork.capability.feedback.MessageFeedback;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerworkapp.service.UserSessionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 消息反馈必须同时满足用户拥有会话、消息属于会话，避免伪造 messageId 覆盖他人反馈。 */
@WebFluxTest(FeedbackController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class,
    ControllerSecurityTestConfiguration.UserAuth.class})
class FeedbackControllerTest {

    private static final String SESSION_ID = "uU1:conv-1";
    private static final String MESSAGE_ID = "MSG-1";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserJwtService jwtService;

    @MockBean
    private FeedbackService feedbackService;

    @MockBean
    private ChatLogService chatLogService;

    @MockBean
    private UserSessionGuard sessionGuard;

    @Test
    void submit_shouldReturn404_whenMessageBelongsToAnotherSession() {
        when(chatLogService.findByMessageId(MESSAGE_ID)).thenReturn(Optional.of(message("uOTHER:conv-9")));

        webTestClient.post().uri("/api/customer/feedback")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request())
            .exchange()
            .expectStatus().isNotFound();

        verify(feedbackService, never()).submit(any(), any(), any(), any());
    }

    @Test
    void submit_shouldPersist_whenSessionAndMessageOwnershipMatch() {
        when(chatLogService.findByMessageId(MESSAGE_ID)).thenReturn(Optional.of(message(SESSION_ID)));
        when(feedbackService.submit(SESSION_ID, MESSAGE_ID, FeedbackType.DOWN, "wrong answer"))
            .thenReturn(new MessageFeedback(MESSAGE_ID, SESSION_ID, FeedbackType.DOWN,
                "wrong answer", 1L));

        webTestClient.post().uri("/api/customer/feedback")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.messageId").isEqualTo(MESSAGE_ID)
            .jsonPath("$.sessionId").isEqualTo(SESSION_ID);

        verify(sessionGuard).requireOwned(SESSION_ID, "U1");
        verify(feedbackService).submit(SESSION_ID, MESSAGE_ID, FeedbackType.DOWN, "wrong answer");
    }

    private String bearer() {
        return "Bearer " + jwtService.issue("U1", "alice", "Alice");
    }

    private String request() {
        return """
            {"sessionId":"%s","messageId":"%s","type":"DOWN","comment":"wrong answer"}
            """.formatted(SESSION_ID, MESSAGE_ID);
    }

    private ChatMessage message(String sessionId) {
        return new ChatMessage(1L, MESSAGE_ID, sessionId, "TK-1",
            TicketActorType.BOT, null, "answer", 1L);
    }
}
