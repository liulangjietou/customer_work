package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerworkapp.chat.AgentMessageAcceptanceService;
import com.richard.fyoung.customerwork.infra.lock.InMemorySessionLock;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用真实内存消息存储验证回复协议；此处鉴权替身仅注入身份，令牌校验另由切片测试覆盖。 */
class AgentMessageReceiptTest {
    private static final String AGENT = "agent-7";
    private static final String TICKET = "TK-1";
    private final TicketService tickets = mock(TicketService.class);
    private final WsSessionRegistry registry = mock(WsSessionRegistry.class);
    private final InMemoryChatMessageStore messages = spy(new InMemoryChatMessageStore());
    private final ChatLogService chatLog = new ChatLogService(messages);
    private Ticket ticket;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ticket = Ticket.create(TICKET, "uU1:desk", "U1", "退款咨询", TicketCategory.CONSULT);
        ticket.requestHandoff("需要人工核对");
        ticket.claim(AGENT);
        when(tickets.find(TICKET)).thenReturn(Optional.of(ticket));
        when(tickets.findForUpdate(TICKET)).thenReturn(Optional.of(ticket));
        client = WebTestClient.bindToController(new AgentTicketController(tickets, chatLog,
                new AgentMessageAcceptanceService(tickets, chatLog, registry, new InMemorySessionLock(10),
                    CustomerWorkTransactionExecutor.DIRECT)))
            .webFilter((exchange, chain) -> {
                exchange.getAttributes().put(AgentAuthWebFilter.AGENT_ID_ATTR, AGENT);
                return chain.filter(exchange)
                    .contextWrite(context -> context.put(TenantContextThreadLocalAccessor.KEY, "tenant-a"));
            }).build();
    }

    @Test
    void repeatedReplyReturnsSavedMessageWithoutSecondPush() {
        ChatMessage first = send("核对后回复您", "request-1").expectStatus().isOk()
            .expectBody(ChatMessage.class).returnResult().getResponseBody();
        ChatMessage replay = send("核对后回复您", "request-1").expectStatus().isOk()
            .expectBody(ChatMessage.class).returnResult().getResponseBody();
        assertEquals(first, replay);
        assertEquals(1, chatLog.historyByTicket(TICKET, null, 10).size());
        verify(registry, times(1)).pushToUser(eq("U1"), any());
    }

    @Test
    void reusedIdWithDifferentContentIsConflict() {
        send("原回复", "request-1").expectStatus().isOk();
        send("修改后的回复", "request-1").expectStatus().isEqualTo(409);
    }

    @Test
    void closedTicketRejectsNewReplyButKeepsReceiptQueryable() {
        ChatMessage first = send("已核对", "request-1").expectStatus().isOk()
            .expectBody(ChatMessage.class).returnResult().getResponseBody();
        ticket.forceClose("完成");
        send("关闭后回复", "request-2").expectStatus().isEqualTo(409);
        client.get().uri("/api/customer/agent/tickets/{id}/receipts/{clientMsgId}", TICKET, "request-1")
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.message.messageId").isEqualTo(first.messageId());
    }

    @Test
    void missingReceiptIsExplicitlyEmpty() {
        client.get().uri("/api/customer/agent/tickets/{id}/receipts/{clientMsgId}", TICKET, "missing")
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.message").isEmpty();
    }

    @Test
    void receiptReadFailureCannotBecomeAStateConflictOrMissingMessage() {
        doThrow(new IllegalStateException("storage unavailable")).when(messages).findByMessageId(any());
        client.get().uri("/api/customer/agent/tickets/{id}/receipts/{clientMsgId}", TICKET, "query-1")
            .exchange().expectStatus().is5xxServerError();
        send("保存状态未知", "query-1").expectStatus().is5xxServerError();
        assertEquals(0, chatLog.historyByTicket(TICKET, null, 10).size());
    }

    @Test
    void transferredTicketKeepsOwnReceiptButRejectsNewRepliesFromPreviousAssignee() {
        ChatMessage first = send("转交前已保存", "request-1").expectStatus().isOk()
            .expectBody(ChatMessage.class).returnResult().getResponseBody();
        ticket.transferToAgent("agent-8");
        send("转交前已保存", "request-1").expectStatus().isOk()
            .expectBody(ChatMessage.class).isEqualTo(first);
        send("转交后不可发送", "request-2").expectStatus().isForbidden();
        client.get().uri("/api/customer/agent/tickets/{id}/receipts/{clientMsgId}", TICKET, "request-1")
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.message.messageId").isEqualTo(first.messageId());
        verify(registry, times(1)).pushToUser(eq("U1"), any());
    }

    @Test
    void invalidInputIsRejectedBeforeSavingAndNotificationFailureKeepsSavedReceipt() {
        send(" \n\t", "blank").expectStatus().isBadRequest();
        send("字".repeat(22_000), "large").expectStatus().isBadRequest();
        send("正常回复", "x".repeat(129)).expectStatus().isBadRequest();
        assertEquals(0, chatLog.historyByTicket(TICKET, null, 10).size());
        doThrow(new IllegalStateException("socket unavailable")).when(registry).pushToUser(eq("U1"), any());
        send("仍应保存", "request-1").expectStatus().isOk();
        assertEquals(1, chatLog.historyByTicket(TICKET, null, 10).size());
    }

    private WebTestClient.ResponseSpec send(String content, String clientMsgId) {
        return client.post().uri("/api/customer/agent/tickets/{id}/reply", TICKET)
            .bodyValue(Map.of("content", content, "clientMsgId", clientMsgId)).exchange();
    }
}
