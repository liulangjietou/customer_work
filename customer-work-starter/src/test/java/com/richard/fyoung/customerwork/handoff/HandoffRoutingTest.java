package com.richard.fyoung.customerwork.handoff;

import com.richard.fyoung.customerwork.routing.HandoffCreatedEnricher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 人机切换工单·智能分配增强字段单测：回写、缺单 fail-open、建单触发增强器。
 * @author owlzhangfq@gmail.com
 */
class HandoffRoutingTest {

    @Test
    void applyRoutingSuggestion_shouldUpdateFields() {
        HandoffService service = new HandoffService();
        HandoffTicket ticket = service.create("s1", "退款");

        service.applyRoutingSuggestion(ticket.getId(), "退款", "refund", "HIGH", "不满", "[{\"seatId\":\"S1\"}]");

        HandoffTicket updated = service.find(ticket.getId()).orElseThrow();
        assertEquals("退款", updated.getCategory());
        assertEquals("refund", updated.getRequiredSkill());
        assertEquals("HIGH", updated.getPriority());
        assertEquals("不满", updated.getEmotion());
        assertEquals("[{\"seatId\":\"S1\"}]", updated.getSuggestedAssignees());
    }

    @Test
    void applyRoutingSuggestion_shouldFailOpen_whenTicketMissing() {
        HandoffService service = new HandoffService();
        // 不存在的工单：只 error 不抛
        service.applyRoutingSuggestion("HO-missing", "x", "y", "LOW", "z", "[]");
    }

    @Test
    void create_shouldTriggerEnricher_whenWired() {
        HandoffService service = new HandoffService();
        HandoffCreatedEnricher enricher = mock(HandoffCreatedEnricher.class);
        service.setEnricher(enricher);

        HandoffTicket ticket = service.create("s2", "转人工");

        verify(enricher).onHandoffCreated(ticket);
    }

    @Test
    void newTicket_shouldHaveNullRoutingFields_byDefault() {
        HandoffTicket ticket = new HandoffTicket("HO-1", "s", "r", System.currentTimeMillis());
        assertNull(ticket.getCategory());
        assertNull(ticket.getSuggestedAssignees());
    }

    @Test
    void create_shouldNotFail_whenEnricherThrows() {
        HandoffService service = new HandoffService();
        HandoffCreatedEnricher enricher = mock(HandoffCreatedEnricher.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(enricher).onHandoffCreated(any());
        service.setEnricher(enricher);

        // 建单主链路不受增强器异常影响
        HandoffTicket ticket = service.create("s3", "转人工");
        assertEquals("s3", ticket.getSessionId());
    }
}
