package com.richard.fyoung.customerwork.capability.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldNotPromiseRefundOrInvoicePolicyWithoutTenantEvidence() {
        for (String message : new String[]{"我要退款", "我要开票"}) {
            AssistSuggestion suggestion = svc.suggest(message);
            String text = suggestion.knowledgeHint() + suggestion.suggestedReply();
            assertFalse(text.contains("七天无理由"), "规则建议不具有当前租户政策依据");
            assertFalse(text.contains("1-3"), "不能在核实前承诺退款到账时间");
            assertFalse(text.contains("24 小时"), "不能在核实前承诺发票发送时间");
        }
    }
}
