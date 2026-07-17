package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.ticket.Ticket;
import com.richard.fyoung.customerwork.ticket.TicketActorType;
import com.richard.fyoung.customerwork.ticket.TicketCategory;
import com.richard.fyoung.customerwork.ticket.TicketService;
import com.richard.fyoung.customerwork.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.ws.WsSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席工单端点切片测试：抢单 409、reply 落库+离线不报错、transfer 两分支。
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(AgentTicketController.class)
@Import({AgentAuthWebFilter.class, AgentTicketControllerTest.Cfg.class})
class AgentTicketControllerTest {

    private static final String SECRET = "agent-ctl-secret";
    private static final String AGENT_ID = "agent-7";

    @TestConfiguration
    static class Cfg {
        @Bean
        CustomerWorkProperties customerWorkProperties() {
            CustomerWorkProperties props = new CustomerWorkProperties();
            props.getAgentAccess().setSecret(SECRET);
            return props;
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TicketService ticketService;

    @MockBean
    private ChatLogService chatLogService;

    @MockBean
    private WsSessionRegistry registry;

    private String token() {
        return AgentAccessCredential.sign(AGENT_ID, System.currentTimeMillis() + 60_000, SECRET);
    }

    private Ticket claimedTicket() {
        Ticket t = Ticket.create("TK-1", "uU1:conv-1", "U1", "标题", TicketCategory.CONSULT);
        t.requestHandoff("x");
        t.claim(AGENT_ID);
        return t;
    }

    @Test
    void claim_alreadyClaimed_shouldReturn409() {
        when(ticketService.claim("TK-1", AGENT_ID))
            .thenThrow(new IllegalStateException("ticket already claimed"));

        webTestClient.post().uri("/api/customer/agent/tickets/TK-1/claim")
            .header("X-Agent-Token", token())
            .exchange()
            .expectStatus().isEqualTo(409);
    }

    @Test
    void reply_shouldPersist_andOfflineUserIsNotAnError() {
        Ticket ticket = claimedTicket();
        when(ticketService.find("TK-1")).thenReturn(Optional.of(ticket));
        when(chatLogService.append(any(), eq("TK-1"), eq(TicketActorType.AGENT), eq(AGENT_ID), eq("您好")))
            .thenReturn(ChatMessage.of("MSG-1", "uU1:conv-1", "TK-1", TicketActorType.AGENT, AGENT_ID, "您好"));
        when(registry.pushToUser(eq("U1"), any())).thenReturn(false); // 用户离线

        webTestClient.post().uri("/api/customer/agent/tickets/TK-1/reply")
            .header("X-Agent-Token", token())
            .bodyValue(Map.of("content", "您好"))
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.messageId").isEqualTo("MSG-1");
    }

    @Test
    void transfer_emptyToAgent_shouldTransferToPool() {
        Ticket ticket = claimedTicket();
        when(ticketService.transferToPool(eq("TK-1"), eq(TicketActorType.AGENT), eq(AGENT_ID)))
            .thenReturn(ticket);

        webTestClient.post().uri("/api/customer/agent/tickets/TK-1/transfer")
            .header("X-Agent-Token", token())
            .bodyValue(Map.of())
            .exchange()
            .expectStatus().isOk();

        verify(ticketService).transferToPool("TK-1", TicketActorType.AGENT, AGENT_ID);
        verify(ticketService, never()).transferToAgent(any(), any(), any(), any());
    }

    @Test
    void transfer_withToAgent_shouldTransferToAgent() {
        Ticket ticket = claimedTicket();
        when(ticketService.transferToAgent(eq("TK-1"), eq("agent-9"), eq(TicketActorType.AGENT), eq(AGENT_ID)))
            .thenReturn(ticket);

        webTestClient.post().uri("/api/customer/agent/tickets/TK-1/transfer")
            .header("X-Agent-Token", token())
            .bodyValue(Map.of("toAgent", "agent-9"))
            .exchange()
            .expectStatus().isOk();

        verify(ticketService).transferToAgent("TK-1", "agent-9", TicketActorType.AGENT, AGENT_ID);
        verify(ticketService, never()).transferToPool(any(), any(), any());
    }

    @Test
    void anyAgentEndpoint_withoutToken_shouldReturn401() {
        webTestClient.get().uri("/api/customer/agent/tickets")
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
