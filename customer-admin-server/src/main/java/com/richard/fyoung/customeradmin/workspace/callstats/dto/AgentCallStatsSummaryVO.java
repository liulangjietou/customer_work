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

    /** 总输入 token（缓存命中率的分母）。 */
    private Long inputTokens;

    /** 总命中缓存的输入 token（inputTokens 的子集，各家通常按 1/10 计价）。 */
    private Long cachedTokens;

    /** 缓存命中率 = cachedTokens / inputTokens；判断 prompt 缓存有没有真生效的唯一直接信号。 */
    private Double cacheHitRate;
}
