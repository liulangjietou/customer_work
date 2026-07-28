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
    private Long totalTokens;
    private BigDecimal avgTotalTokens;
    /** 命中缓存的输入 token 合计（inputTokens 的子集）。 */
    private Long cachedTokens;
    /** 输入 token 合计——与 cachedTokens 配对才能算缓存命中率，单独给缓存量没有意义。 */
    private Long inputTokens;
}
