package com.richard.fyoung.customerwork.routing;

import com.richard.fyoung.customerwork.assist.ConversationSummaryService;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.handoff.HandoffService;
import com.richard.fyoung.customerwork.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.observability.AuditSink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转人工增强器单测：分类打分结果回写工单（HITL 推荐），以及 fail-open（分类抛异常不影响转人工、不改工单）。
 * LLM 相关依赖（摘要服务/分类器）用 mock 隔离；打分器与坐席库用真实实现（确定性）。
 * @author owlzhangfq@gmail.com
 */
class HandoffCreatedEnricherTest {

    private final ConversationSummaryService summaryService = mock(ConversationSummaryService.class);
    private final TicketClassifier classifier = mock(TicketClassifier.class);
    private final SeatRoutingScorer scorer = new SeatRoutingScorer();
    private final SeatAgentStore seatStore = new InMemorySeatAgentStore();
    private final AuditSink auditSink = mock(AuditSink.class);

    private HandoffCreatedEnricher enricher(CustomerWorkProperties props, HandoffService handoffService) {
        return new HandoffCreatedEnricher(handoffService, summaryService, classifier, scorer,
            seatStore, props, auditSink);
    }

    @Test
    void enrich_shouldWriteRoutingSuggestion_whenAssignEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getRouting().setAssignEnabled(true);
        when(classifier.classify(anyString(), any()))
            .thenReturn(new TicketClassification("退款", "refund", TicketPriority.HIGH, "不满"));

        HandoffService handoffService = new HandoffService();
        HandoffTicket ticket = handoffService.create("sess-1", "涉及大额退款");

        enricher(props, handoffService).enrich(ticket);

        HandoffTicket persisted = handoffService.find(ticket.getId()).orElseThrow();
        assertEquals("退款", persisted.getCategory());
        assertEquals("HIGH", persisted.getPriority());
        assertEquals("refund", persisted.getRequiredSkill());
        // 推荐坐席已写入（refund 技能坐席应命中）
        assertTrue(persisted.getSuggestedAssignees() != null
            && persisted.getSuggestedAssignees().contains("SEAT-"));
        verify(auditSink).record(any(), any());
    }

    @Test
    void enrich_shouldFailOpen_whenClassifierThrows() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getRouting().setAssignEnabled(true);
        when(classifier.classify(anyString(), any())).thenThrow(new RuntimeException("boom"));

        HandoffService handoffService = new HandoffService();
        HandoffTicket ticket = handoffService.create("sess-2", "投诉");

        // 不抛异常（fail-open）
        enricher(props, handoffService).enrich(ticket);

        HandoffTicket persisted = handoffService.find(ticket.getId()).orElseThrow();
        assertTrue(persisted.getCategory() == null, "分类失败不应写入任何推荐");
        assertTrue(persisted.getSuggestedAssignees() == null);
    }

    @Test
    void onHandoffCreated_shouldDoNothing_whenBothFlagsOff() {
        CustomerWorkProperties props = new CustomerWorkProperties(); // 默认全关
        HandoffService handoffService = new HandoffService();
        HandoffTicket ticket = handoffService.create("sess-3", "咨询");

        enricher(props, handoffService).onHandoffCreated(ticket);

        verify(classifier, never()).classify(anyString(), any());
        verify(summaryService, never()).summarize(anyString());
        assertFalse(handoffService.find(ticket.getId()).orElseThrow().getSuggestedAssignees() != null);
    }
}
