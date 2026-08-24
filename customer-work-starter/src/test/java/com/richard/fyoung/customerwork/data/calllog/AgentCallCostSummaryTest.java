package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.core.model.attribution.ModelCallAttribution;
import com.richard.fyoung.customerwork.core.model.attribution.ModelPricingStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentCallCostSummaryTest {

    @Test
    void from_shouldPreciselySumSameCurrencyModelSegments() {
        AgentCallCostSummary summary = AgentCallCostSummary.from(List.of(
            model(1, "CNY", 100L, 20L),
            model(2, "CNY", 200L, 40L),
            tool(3)));

        assertEquals(AgentCallCostStatus.COMPLETE, summary.status());
        assertEquals("CNY", summary.currency());
        assertEquals(new BigDecimal("0.00084000000000"), summary.settledAmount());
        assertEquals(2, summary.modelSegmentCount());
        assertEquals(2, summary.settledSegmentCount());
        assertEquals(0, summary.unsettledSegmentCount());
    }

    @Test
    void from_shouldKeepKnownAmountButMarkPartialWhenOneSegmentCannotSettle() {
        AgentCallSegment unpriced = new AgentCallSegment(2, AgentCallKind.MODEL, "qwen", 0L, 1L,
            true, null, 10L, 2L, 0L, null,
            ModelCallAttribution.unpriced("dashscope", 2L, "qwen"));

        AgentCallCostSummary summary = AgentCallCostSummary.from(List.of(
            model(1, "CNY", 100L, 20L), unpriced));

        assertEquals(AgentCallCostStatus.PARTIAL, summary.status());
        assertEquals(new BigDecimal("0.00028000000000"), summary.settledAmount());
        assertEquals(1, summary.unsettledSegmentCount());
    }

    @Test
    void from_shouldNeverAddDifferentCurrencies() {
        AgentCallCostSummary summary = AgentCallCostSummary.from(List.of(
            model(1, "CNY", 100L, 20L),
            model(2, "USD", 100L, 20L)));

        assertEquals(AgentCallCostStatus.MULTI_CURRENCY, summary.status());
        assertNull(summary.settledAmount());
        assertNull(summary.currency());
    }

    private AgentCallSegment model(int seq, String currency, long input, long output) {
        ModelCallAttribution attribution = new ModelCallAttribution("dashscope", (long) seq,
            "qwen", (long) seq, currency, new BigDecimal("2.00000000"),
            new BigDecimal("4.00000000"), new BigDecimal("0.20000000"),
            ModelPricingStatus.PRICED);
        return new AgentCallSegment(seq, AgentCallKind.MODEL, "qwen", 0L, 1L, true, null,
            input, output, 0L, null, attribution);
    }

    private AgentCallSegment tool(int seq) {
        return new AgentCallSegment(seq, AgentCallKind.TOOL, "tool", 0L, 1L, true, null,
            null, null, null, null);
    }
}
