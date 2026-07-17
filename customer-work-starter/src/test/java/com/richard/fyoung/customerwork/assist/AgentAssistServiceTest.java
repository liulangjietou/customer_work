package com.richard.fyoung.customerwork.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐席辅助单测（离线确定性，规则匹配）。
 * @author owlzhangfq@gmail.com
 */
class AgentAssistServiceTest {

    private final AgentAssistService svc = new AgentAssistService();

    @Test
    void shouldSuggestRefundFlow() {
        AssistSuggestion s = svc.suggest("我要退款");
        assertTrue(s.recommendedTool().contains("Refund"));
    }

    @Test
    void shouldSuggestComplaintFlow() {
        AssistSuggestion s = svc.suggest("我要投诉客服态度");
        assertTrue(s.recommendedTool().contains("fileComplaint"));
        assertTrue(s.knowledgeHint().contains("情绪") || s.suggestedReply().contains("致歉"));
    }

    @Test
    void shouldFallbackToKnowledge() {
        AssistSuggestion s = svc.suggest("你好");
        assertTrue(s.recommendedTool().contains("searchKnowledge"));
    }
}
