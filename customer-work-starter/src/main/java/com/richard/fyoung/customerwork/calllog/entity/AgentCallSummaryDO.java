package com.richard.fyoung.customerwork.calllog.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 汇总统计查询结果承载（{@code AVG} 返回 {@link BigDecimal}，由 Store 转成领域 {@code AgentCallLogSummary}）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallSummaryDO {

    private Long totalCount;
    private BigDecimal avgDurationMs;
    private Long maxDurationMs;
    private BigDecimal avgModelMs;
    private BigDecimal avgToolMs;
    private BigDecimal avgMcpMs;
    private BigDecimal avgSkillMs;
}
