package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 调用耗时统计分页行（列表视图，问题/回答按预览截断）。时间字段输出 {@code yyyy-MM-dd HH:mm:ss}。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallStatsRowVO {

    private Long id;
    private String requestId;
    private String userId;
    private String username;
    private String agentCode;
    private String agentName;
    private String sessionId;
    private String sessionType;
    /** OTel/Tempo 关联标识；未开启追踪时为空。 */
    private String traceId;
    /** 当前实例已应用的发布修订；仅本地 yml 启动时为空。 */
    private String runtimeRevision;
    private Long experimentId;
    private Integer experimentRevision;
    private String experimentArm;
    private Long experimentDeploymentId;
    private Integer experimentBucket;
    /** 用户问题（截断至预览长度）。 */
    private String question;
    /** 智能体回答预览（截断至预览长度）。 */
    private String answerPreview;
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
    /** COMPLETE 时为调用完整模型成本，PARTIAL 时仅为已结算部分。 */
    private BigDecimal modelCostAmount;
    private String modelCostCurrency;
    private String modelCostStatus;
    private Integer modelSegmentCount;
    private Integer settledCostSegmentCount;
    private Integer unsettledCostSegmentCount;
}
