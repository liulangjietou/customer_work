package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerworkapp.service.UserSessionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 用户工单端点切片测试：越权隐藏为 404、非法流转 409、正常建会话/确认链路。
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(UserTicketController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class,
    ControllerSecurityTestConfiguration.UserAuth.class})
class UserTicketControllerTest {

    private static final String USER_ID = "U1";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserJwtService jwtService;

    @MockBean
    private TicketService ticketService;

    @MockBean
    private ChatLogService chatLogService;

    @MockBean
    private UserSessionGuard userSessionGuard;

    private String bearer() {
        return "Bearer " + jwtService.issue(USER_ID, "alice", "Alice");
    }

    private Ticket ownedTicket() {
        return Ticket.create("TK-1", "uU1:conv-1", USER_ID, "标题", TicketCategory.CONSULT);
    }

    @Test
    void getTicket_notOwner_shouldReturn404() {
        Ticket foreign = Ticket.create("TK-9", "uOTHER:conv", "OTHER", "t", TicketCategory.CONSULT);
        when(ticketService.find("TK-9")).thenReturn(Optional.of(foreign));

        webTestClient.get().uri("/api/customer/user/tickets/TK-9")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void confirm_illegalTransition_shouldReturn409() {
        when(ticketService.find("TK-1")).thenReturn(Optional.of(ownedTicket()));
        when(ticketService.confirm(eq("TK-1"), any(), any()))
            .thenThrow(new IllegalStateException("illegal ticket transition"));

        webTestClient.post().uri("/api/customer/user/tickets/TK-1/confirm")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isEqualTo(409);
    }

    @Test
    void createSession_shouldReturnSessionAndTicketId() {
        Ticket created = ownedTicket();
        when(ticketService.createForSession(anyString(), eq(USER_ID), isNull(), eq(TicketCategory.CONSULT)))
            .thenReturn(created);

        webTestClient.post().uri("/api/customer/user/sessions")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.ticketId").isEqualTo("TK-1")
            .jsonPath("$.sessionId").value(id ->
                org.assertj.core.api.Assertions.assertThat((String) id).startsWith("uU1:"));
    }

    @Test
    void createSession_whenActiveSessionExists_shouldReturn409WithSessionInfo() {
        Ticket active = ownedTicket(); // sessionId=uU1:conv-1, status=AI_SERVING
        when(ticketService.findActiveByUser(USER_ID)).thenReturn(Optional.of(active));

        webTestClient.post().uri("/api/customer/user/sessions")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("TICKET-ACTIVE-SESSION-EXISTS")
            .jsonPath("$.ticketId").isEqualTo("TK-1")
            .jsonPath("$.sessionId").isEqualTo("uU1:conv-1")
            .jsonPath("$.status").isEqualTo("AI_SERVING");
    }

    @Test
    void reopen_whenNoOtherActiveSession_shouldSucceed() {
        Ticket owned = ownedTicket(); // TK-1，closed 状态由 service 内部校验，这里只关心 controller 层前置校验
        when(ticketService.find("TK-1")).thenReturn(Optional.of(owned));
        when(ticketService.findActiveByUser(USER_ID)).thenReturn(Optional.empty());
        when(ticketService.reopenToAi(eq("TK-1"), any(), eq(TicketActorType.USER), eq(USER_ID))).thenReturn(owned);

        webTestClient.post().uri("/api/customer/user/tickets/TK-1/reopen")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo("TK-1");
    }

    @Test
    void reopen_whenAnotherActiveSessionExists_shouldReturn409WithSessionInfo() {
        // 待重开的历史工单 TK-1，用户当前另有一张进行中工单 TK-2（uU1:conv-2）
        Ticket toReopen = ownedTicket();
        Ticket active = Ticket.create("TK-2", "uU1:conv-2", USER_ID, "标题2", TicketCategory.CONSULT);
        when(ticketService.find("TK-1")).thenReturn(Optional.of(toReopen));
        when(ticketService.findActiveByUser(USER_ID)).thenReturn(Optional.of(active));

        webTestClient.post().uri("/api/customer/user/tickets/TK-1/reopen")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("TICKET-ACTIVE-SESSION-EXISTS")
            .jsonPath("$.ticketId").isEqualTo("TK-2")
            .jsonPath("$.sessionId").isEqualTo("uU1:conv-2")
            .jsonPath("$.status").isEqualTo("AI_SERVING");
    }

    @Test
    void reopen_whenActiveSessionIsSameTicket_shouldSucceed() {
        // findActiveByUser 命中的正是自己（边界防御：即便出现该状态，也不应误判为"另一张活跃会话"而拒绝）
        Ticket owned = ownedTicket();
        when(ticketService.find("TK-1")).thenReturn(Optional.of(owned));
        when(ticketService.findActiveByUser(USER_ID)).thenReturn(Optional.of(owned));
        when(ticketService.reopenToAi(eq("TK-1"), any(), eq(TicketActorType.USER), eq(USER_ID))).thenReturn(owned);

        webTestClient.post().uri("/api/customer/user/tickets/TK-1/reopen")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo("TK-1");
    }

    @Test
    void close_withForce_shouldForceClose() {
        Ticket owned = ownedTicket();
        when(ticketService.find("TK-1")).thenReturn(Optional.of(owned));
        when(ticketService.forceClose(eq("TK-1"), anyString(), eq(TicketActorType.USER), eq(USER_ID)))
            .thenReturn(owned);

        webTestClient.post().uri("/api/customer/user/tickets/TK-1/close")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(Map.of("force", true))
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo("TK-1");
    }

    @Test
    void close_nonForceIllegalTransition_shouldReturn409() {
        when(ticketService.find("TK-1")).thenReturn(Optional.of(ownedTicket()));
        when(ticketService.close(eq("TK-1"), any(), any(), any()))
            .thenThrow(new IllegalStateException("illegal ticket transition"));

        webTestClient.post().uri("/api/customer/user/tickets/TK-1/close")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(Map.of("force", false))
            .exchange()
            .expectStatus().isEqualTo(409);
    }

    @Test
    void confirm_owned_shouldSucceed() {
        Ticket owned = ownedTicket();
        when(ticketService.find("TK-1")).thenReturn(Optional.of(owned));
        when(ticketService.confirm(eq("TK-1"), eq(TicketActorType.USER), eq(USER_ID))).thenReturn(owned);

        webTestClient.post().uri("/api/customer/user/tickets/TK-1/confirm")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo("TK-1");
    }

    @Test
    void anyUserEndpoint_withoutToken_shouldReturn401() {
        webTestClient.get().uri("/api/customer/user/tickets")
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
