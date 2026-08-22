package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import jakarta.validation.constraints.Min;

/** 路由 dry-run 上下文；preferFallback 对应 quota DEGRADE 的既有强制备用语义。 */
public record ModelRouteDryRunRequest(Long agentId,
                                      String channelCode,
                                      @Min(value = 0, message = "inputTokens 不能小于 0") Integer inputTokens,
                                      Boolean requiresTools,
                                      Boolean requiresStructuredOutput,
                                      String complexity,
                                      Boolean preferFallback) {
}
