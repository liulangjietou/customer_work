package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.assist.ConversationSummary;
import com.richard.fyoung.customerwork.capability.assist.ConversationSummaryService;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实 WebFilter 和异步控制器验证令牌、租户来源以及浏览器不能另选会话。 */
class AgentTicketAssistControllerTest {
    private static final String SECRET = "assist-controller-test-secret";
    private final TicketService tickets = mock(TicketService.class);
    private final ConversationSummaryService summaries = mock(ConversationSummaryService.class);
    private WebTestClient client;

    @BeforeEach
    void setup() {
        var properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(SECRET);
        client = WebTestClient.bindToController(new AgentTicketAssistController(tickets, summaries))
            .webFilter(new AgentAuthWebFilter(properties)).build();
        when(tickets.find("ticket-a")).thenAnswer(invocation ->
            "tenant-a".equals(TenantContext.get())
                ? Optional.of(Ticket.create("ticket-a", "trusted-conversation", "user-a", "开票问题", TicketCategory.OTHER))
                : Optional.empty());
    }

    @Test
    void summaryMustUseTheAuthorizedTicketSessionOnTheWorker() {
        when(summaries.readCurrent("trusted-conversation")).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return new ConversationSummary("用户咨询开票", "开票", null, List.of(), List.of(),
                "查证订单记录", "我会先核对开票记录", false);
        });
        client.get().uri("/api/customer/agent/tickets/ticket-a/assist?sessionId=foreign-conversation")
            .header("X-Agent-Token", token("tenant-a")).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.ticketId").isEqualTo("ticket-a")
            .jsonPath("$.summary.userIntent").isEqualTo("开票");
        verify(summaries).readCurrent("trusted-conversation");
        verify(summaries, never()).summarize(anyString());
        verify(summaries, never()).readCurrent("foreign-conversation");
    }

    @Test
    void foreignTenantMustNotReachConversationHistory() {
        client.get().uri("/api/customer/agent/tickets/ticket-a/assist")
            .header("X-Agent-Token", token("tenant-b")).exchange().expectStatus().isNotFound();
        verifyNoInteractions(summaries);
    }

    @Test
    void missingOrSubscriptionOnlyTokensMustNotReachTheTicketStore() {
        client.get().uri("/api/customer/agent/tickets/ticket-a/assist")
            .exchange().expectStatus().isUnauthorized();
        var subscription = AgentAccessCredential.signSubscription("operator", "tenant-a",
            System.currentTimeMillis() + 60_000, SECRET);
        client.get().uri("/api/customer/agent/tickets/ticket-a/assist")
            .header("X-Agent-Token", subscription).exchange().expectStatus().isForbidden();
        verifyNoInteractions(tickets, summaries);
    }

    @Test
    void failedHistoryMustRemainAnErrorInsteadOfAnEmptySummary() {
        when(summaries.readCurrent("trusted-conversation")).thenThrow(new IllegalStateException("history down"));
        client.get().uri("/api/customer/agent/tickets/ticket-a/assist")
            .header("X-Agent-Token", token("tenant-a")).exchange().expectStatus().is5xxServerError();
    }

    private String token(String tenant) {
        return AgentAccessCredential.sign("operator", tenant, System.currentTimeMillis() + 60_000, SECRET);
    }
}
