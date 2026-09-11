package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import com.richard.fyoung.customerwork.core.service.ChatTurnEvent;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.ws.InboundMessageDeduplicator;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 去重测试必须覆盖受理失败、回执丢失和重启，单纯断言模型只调用一次会漏掉消息被吞。 */
class ChatMessageAcceptanceTest {
    private static final String SESSION = "uU1:acceptance";
    private final UserPrincipal user = new UserPrincipal("U1", "alice", "Alice", "tenant-a");
    private final InMemoryChatMessageStore store = spy(new InMemoryChatMessageStore());
    private final ChatLogService chatLog = new ChatLogService(store);
    private final TicketService tickets = mock(TicketService.class);
    private final ChatTurnService turns = mock(ChatTurnService.class);
    private final WsSessionRegistry registry = mock(WsSessionRegistry.class);
    private final SubjectQuotaGuard quota = mock(SubjectQuotaGuard.class);
    private final HandoffKeywordDetector keywords = mock(HandoffKeywordDetector.class);
    private ChatDispatchService dispatch;

    @BeforeEach
    void setUp() {
        when(tickets.findActiveBySession(anyString())).thenAnswer(call -> Optional.of(
            Ticket.create("TK-1", call.getArgument(0), "U1", null, TicketCategory.CONSULT)));
        when(quota.check(any(), any())).thenReturn(SubjectQuotaDecision.allow());
        when(turns.stream(anyString(), anyString(), anyString())).thenReturn(Flux.empty());
        dispatch = newDispatch();
    }

    @Test
    void failedPersistence_shouldAllowRetryWithTheSameClientId() {
        doThrow(new IllegalStateException("database unavailable")).doCallRealMethod().when(store).append(any());
        send(dispatch, SESSION, "查询进度", "request-1");
        send(dispatch, SESSION, "查询进度", "request-1");
        assertEquals(1, chatLog.historyBySession(SESSION, null, 10).size());
        verify(turns, times(1)).stream(SESSION, "查询进度", "TK-1");
        verify(quota, times(1)).recordRequest(any());
    }

    @Test
    void rejectedQuota_shouldNotReserveTheClientId() {
        SubjectQuotaDecision denied = mock(SubjectQuotaDecision.class);
        when(denied.shouldBlock()).thenReturn(true);
        when(denied.message()).thenReturn("请求过于频繁");
        when(quota.check(any(), any())).thenReturn(denied, SubjectQuotaDecision.allow());
        send(dispatch, SESSION, "查询进度", "request-2");
        send(dispatch, SESSION, "查询进度", "request-2");
        assertEquals(1, chatLog.historyBySession(SESSION, null, 10).size());
        verify(turns, times(1)).stream(SESSION, "查询进度", "TK-1");
    }

    @Test
    void duplicateDelivery_shouldReturnTheSamePersistedReceipt() {
        send(dispatch, SESSION, "查询进度", "request-3");
        send(dispatch, SESSION, "查询进度", "request-3");
        List<Map<?, ?>> receipts = frames("chat_accepted");
        assertEquals(2, receipts.size(), "首次和重复投递都应有可核对的受理回执");
        var message = chatLog.historyBySession(SESSION, null, 10).get(0);
        receipts.forEach(receipt -> {
            assertEquals("request-3", receipt.get("clientMsgId"));
            assertEquals(SESSION, receipt.get("sessionId"));
            assertEquals(message.messageId(), receipt.get("messageId"));
            assertEquals(message.id(), ((Number) receipt.get("id")).longValue());
        });
        verify(turns, times(1)).stream(SESSION, "查询进度", "TK-1");
    }

    @Test
    void sameClientIdWithDifferentContent_shouldReturnConflict() {
        send(dispatch, SESSION, "第一条", "request-4");
        send(dispatch, SESSION, "被改动的内容", "request-4");
        List<Map<?, ?>> rejected = frames("error");
        assertEquals(1, rejected.size());
        assertEquals("CHAT_MESSAGE_ID_CONFLICT", rejected.get(0).get("code"));
        assertEquals("request-4", rejected.get(0).get("clientMsgId"));
        verify(turns, never()).stream(SESSION, "被改动的内容", "TK-1");
    }

    @Test
    void clientId_shouldBeScopedToSession() {
        send(dispatch, SESSION, "查询进度", "request-5");
        send(dispatch, "uU1:other", "查询进度", "request-5");
        assertEquals(1, chatLog.historyBySession("uU1:other", null, 10).size());
        verify(turns).stream("uU1:other", "查询进度", "TK-1");
    }

    @Test
    void newServerInstance_shouldReconcileReceiptWithoutRepeatingTheTurn() {
        send(dispatch, SESSION, "查询进度", "request-6");
        send(newDispatch(), SESSION, "查询进度", "request-6");
        assertEquals(1, chatLog.historyBySession(SESSION, null, 10).size());
        assertEquals(2, frames("chat_accepted").size());
        verify(turns, times(1)).stream(SESSION, "查询进度", "TK-1");
    }

    @Test
    void blankMessage_shouldBeRejectedBeforeQuotaOrPersistence() {
        send(dispatch, SESSION, "  \n\t", "empty-1");
        assertEquals(1, frames("error").size(), "空消息必须明确拒绝");
        assertEquals("REJECTED", frames("error").get(0).get("acceptance"));
        assertEquals("empty-1", frames("error").get(0).get("clientMsgId"));
        verifyNoInteractions(tickets, quota, turns);
        verify(store, never()).append(any());
    }

    @Test
    void foreignSession_shouldReturnCorrelatedRejectionWithoutReadingHistory() {
        send(dispatch, "uOTHER:private", "不要访问", "foreign-1");
        assertEquals("REJECTED", frames("error").get(0).get("acceptance"));
        assertEquals("foreign-1", frames("error").get(0).get("clientMsgId"));
        verifyNoInteractions(tickets, quota, turns, store);
    }

    @Test
    void excessivelyLargeMessage_shouldBeRejectedInsteadOfReturningUnknownDatabaseFailure() {
        send(dispatch, SESSION, "字".repeat(22_000), "large-1");
        assertEquals(1, frames("error").size(), "超过实际数据库容量的消息必须在写入前拒绝");
        assertEquals("REJECTED", frames("error").get(0).get("acceptance"));
        verifyNoInteractions(tickets, quota, turns);
        verify(store, never()).append(any());
    }

    @Test
    void asynchronousReplyFrames_shouldRetainTheAcceptingTenant() {
        List<String> tenants = new CopyOnWriteArrayList<>();
        when(registry.pushToUser(eq("U1"), any())).thenAnswer(call -> {
            tenants.add(String.valueOf(TenantContext.get()));
            return true;
        });
        when(turns.stream(anyString(), anyString(), anyString())).thenReturn(
            Flux.just(new ChatTurnEvent.Delta("异步回复")).cast(ChatTurnEvent.class)
                .publishOn(Schedulers.parallel()));
        send(dispatch, SESSION, "查询进度", "tenant-stream");
        assertEquals(List.of("tenant-a", "tenant-a"), tenants, "回执与异步回复必须投递到同一租户");
        assertNull(TenantContext.get());
    }

    @Test
    void forwardingAfterAcceptance_shouldRetainTheAcceptingTenant() {
        Ticket ticket = Ticket.create("TK-1", SESSION, "U1", null, TicketCategory.CONSULT);
        ticket.requestHandoff("转人工");
        ticket.claim("agent-1");
        when(tickets.findActiveBySession(SESSION)).thenReturn(Optional.of(ticket));
        var tenant = new AtomicReference<String>();
        when(registry.pushToAgent(eq("agent-1"), any())).thenAnswer(call -> {
            tenant.set(TenantContext.get());
            return true;
        });
        send(dispatch, SESSION, "人工咨询", "tenant-agent");
        assertEquals("tenant-a", tenant.get());
        assertNull(TenantContext.get());
    }

    private ChatDispatchService newDispatch() {
        return new ChatDispatchService(tickets, chatLog, turns, keywords, registry, quota,
            new InboundMessageDeduplicator(new InMemoryWindowCounter(), 300));
    }

    private void send(ChatDispatchService service, String session, String content, String clientId) {
        StepVerifier.create(service.onUserMessage(user, session, content, clientId)).verifyComplete();
    }

    private List<Map<?, ?>> frames(String type) {
        ArgumentCaptor<WsFrame> captor = ArgumentCaptor.forClass(WsFrame.class);
        verify(registry, atLeastOnce()).pushToUser(eq("U1"), captor.capture());
        return captor.getAllValues().stream().filter(frame -> type.equals(frame.type()))
            .<Map<?, ?>>map(frame -> (Map<?, ?>) frame.data()).toList();
    }
}
