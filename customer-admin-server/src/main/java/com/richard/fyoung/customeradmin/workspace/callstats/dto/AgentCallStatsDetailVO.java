package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
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
    private String traceId;
    private String runtimeRevision;
    private String runtimeContentHash;
    private Long experimentId;
    private Integer experimentRevision;
    private String experimentArm;
    private Long experimentDeploymentId;
    private Integer experimentBucket;
    /** 不含任何 API Key/MCP header 的制品版本绑定。 */
    private EvalVersionBinding versionBinding;
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
    /** 命中缓存的输入 token（inputTokens 的子集，不计入 totalTokens）。 */
    private Long cachedTokens;
    /** 模型自报耗时合计（毫秒），与 modelMs 之差即网络/排队开销。 */
    private Long modelReportedMs;
    private List<AgentCallSegmentVO> segments;
}
