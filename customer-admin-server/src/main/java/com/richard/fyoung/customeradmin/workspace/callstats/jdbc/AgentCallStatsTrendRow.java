package com.richard.fyoung.customeradmin.workspace.callstats.jdbc;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 趋势聚合查询结果承载（一个时间桶）。相比 starter 的 {@code AgentCallTrendDO} 多出各段平均耗时列，
 * 满足前端契约里 trend 需返回 avgModel/Tool/Mcp/Skill 的要求。{@code AVG} 返回 {@link BigDecimal}。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallStatsTrendRow {

    private String bucket;
    private Long cnt;
    private BigDecimal avgDurationMs;
    private BigDecimal avgModelMs;
    private BigDecimal avgToolMs;
    private BigDecimal avgMcpMs;
    private BigDecimal avgSkillMs;
    /** 该桶内 token 消耗合计（SUM(total_tokens)，缺失记 0）。 */
    private Long totalTokens;
}
