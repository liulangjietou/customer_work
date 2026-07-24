package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import lombok.Data;

/**
 * 调用耗时趋势的一个时间桶（按天 {@code yyyy-MM-dd} 或按小时 {@code yyyy-MM-dd HH}）。
 * 各段平均耗时随桶给出，供前端画分段耗时趋势。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallTrendVO {

    private String bucket;
    private Long count;
    private Double avgDurationMs;
    private Double avgModelMs;
    private Double avgToolMs;
    private Double avgMcpMs;
    private Double avgSkillMs;
}
