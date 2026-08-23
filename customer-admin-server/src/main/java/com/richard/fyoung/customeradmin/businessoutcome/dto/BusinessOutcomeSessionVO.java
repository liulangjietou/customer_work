package com.richard.fyoung.customeradmin.businessoutcome.dto;

/** 单会话的可解释代理结果；不把技术成功冒充为真实业务解决。 */
public record BusinessOutcomeSessionVO(
    String sessionId,
    String agentCodes,
    long firstCallAtMs,
    long lastCallAtMs,
    long callCount,
    boolean successful,
    boolean handedOff,
    boolean autoResolvedProxy,
    Long totalTokens,
    MetricAvailability tokenAvailability,
    Integer csatScore
) {
}
