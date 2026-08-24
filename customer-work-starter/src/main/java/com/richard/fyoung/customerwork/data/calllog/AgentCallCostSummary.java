package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.core.model.attribution.ModelCallCost;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次 Agent 调用内全部 MODEL 分段的金额汇总。
 *
 * <p>混合币种不产生伪合计；部分结算只保留“已结算金额”并标记 PARTIAL，下游只有 COMPLETE
 * 才能用于单次自动解决成本。</p>
 */
public record AgentCallCostSummary(BigDecimal settledAmount,
                                   String currency,
                                   AgentCallCostStatus status,
                                   int modelSegmentCount,
                                   int settledSegmentCount,
                                   int unsettledSegmentCount) {

    public static AgentCallCostSummary from(List<AgentCallSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return empty();
        }
        int modelCount = 0;
        int settledCount = 0;
        BigDecimal amount = BigDecimal.ZERO.setScale(ModelCallCost.AMOUNT_SCALE);
        Set<String> settledCurrencies = new LinkedHashSet<>();
        for (AgentCallSegment segment : segments) {
            if (segment == null || segment.kind() != AgentCallKind.MODEL) {
                continue;
            }
            modelCount++;
            ModelCallCost cost = segment.cost();
            if (cost.settled()) {
                settledCount++;
                amount = amount.add(cost.amount());
                settledCurrencies.add(cost.currency());
            }
        }
        if (modelCount == 0) {
            return empty();
        }
        int unsettledCount = modelCount - settledCount;
        if (settledCount == 0) {
            return new AgentCallCostSummary(null, null, AgentCallCostStatus.UNAVAILABLE,
                modelCount, 0, unsettledCount);
        }
        if (settledCurrencies.size() != 1) {
            return new AgentCallCostSummary(null, null, AgentCallCostStatus.MULTI_CURRENCY,
                modelCount, settledCount, unsettledCount);
        }
        AgentCallCostStatus status = unsettledCount == 0
            ? AgentCallCostStatus.COMPLETE : AgentCallCostStatus.PARTIAL;
        return new AgentCallCostSummary(amount, settledCurrencies.iterator().next(), status,
            modelCount, settledCount, unsettledCount);
    }

    private static AgentCallCostSummary empty() {
        return new AgentCallCostSummary(null, null, AgentCallCostStatus.NO_MODEL, 0, 0, 0);
    }
}
