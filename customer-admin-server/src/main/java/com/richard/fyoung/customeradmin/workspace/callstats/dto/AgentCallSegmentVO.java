package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import lombok.Data;

/**
 * 调用明细中的一段耗时（MODEL/TOOL/MCP/SKILL）。{@code startTime} 输出 {@code yyyy-MM-dd HH:mm:ss}。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallSegmentVO {

    private Integer seq;
    private String kind;
    private String name;
    private String startTime;
    private Long durationMs;
    /** token 消耗（仅 MODEL 段有值，缺失为 null）。 */
    private Long inputTokens;
    private Long outputTokens;
    private Boolean success;
    private String errorMsg;
}
