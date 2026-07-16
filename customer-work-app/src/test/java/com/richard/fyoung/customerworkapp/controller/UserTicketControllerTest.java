package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.ticket.Ticket;
import com.richard.fyoung.customerwork.ticket.TicketActorType;
import com.richard.fyoung.customerwork.ticket.TicketCategory;
import com.richard.fyoung.customerwork.ticket.TicketService;
import com.richard.fyoung.customerwork.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.security.UserJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 用户工单端点切片测试：越权 403、非法流转 409、正常建会话/确认链路。
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(UserTicketController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class, UserAuthWebFilter.class})
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

    private String bearer() {
        return "Bearer " + jwtService.issue(USER_ID, "alice", "Alice");
    }

    private Ticket ownedTicket() {
        return Ticket.create("TK-1", "uU1:conv-1", USER_ID, "标题", TicketCategory.CONSULT);
    }

    @Test
    void getTicket_notOwner_shouldReturn403() {
        Ticket foreign = Ticket.create("TK-9", "uOTHER:conv", "OTHER", "t", TicketCategory.CONSULT);
        when(ticketService.find("TK-9")).thenReturn(Optional.of(foreign));

        webTestClient.get().uri("/api/customer/user/tickets/TK-9")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isForbidden();
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
