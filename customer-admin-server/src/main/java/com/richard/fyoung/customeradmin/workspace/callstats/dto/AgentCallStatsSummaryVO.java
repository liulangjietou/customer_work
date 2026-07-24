package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import lombok.Data;

/**
 * 调用耗时汇总（符合筛选条件的整体口径）。平均值单位毫秒。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallStatsSummaryVO {

    private Long totalCalls;
    private Double avgDurationMs;
    private Long maxDurationMs;
    private Double avgModelMs;
    private Double avgToolMs;
    private Double avgMcpMs;
    private Double avgSkillMs;
    /** 总 token 消耗（符合条件记录 total_tokens 求和）。 */
    private Long totalTokens;
    /** 平均每次 token 消耗。 */
    private Double avgTotalTokens;
}
