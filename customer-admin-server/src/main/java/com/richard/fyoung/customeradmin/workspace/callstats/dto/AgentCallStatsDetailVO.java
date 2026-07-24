package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import lombok.Data;

import java.util.List;

/**
 * 调用耗时统计明细（详情视图）：主记录全量字段（含回答全文）+ 分段明细列表（seq 升序）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallStatsDetailVO {

    private Long id;
    private String requestId;
    private String userId;
    private String username;
    private String agentCode;
    private String agentName;
    private String sessionId;
    private String sessionType;
    private String question;
    /** 智能体回答全文（不截断）。 */
    private String answer;
    private String startTime;
    private String endTime;
    private Long durationMs;
    private Long modelMs;
    private Long toolMs;
    private Long mcpMs;
    private Long skillMs;
    /** 请求级 token 消耗（缺失为 null）。 */
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private List<AgentCallSegmentVO> segments;
}
