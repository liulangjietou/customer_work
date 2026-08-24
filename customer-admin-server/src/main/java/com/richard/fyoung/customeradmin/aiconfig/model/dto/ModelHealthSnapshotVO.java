package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.time.LocalDateTime;

/** 模型健康快照视图。 */
public record ModelHealthSnapshotVO(String healthStatus,
                                    String effectiveHealthStatus,
                                    boolean routingAvailable,
                                    String authStatus,
                                    String capabilityStatus,
                                    Integer consecutiveFailures,
                                    Integer consecutiveSuccesses,
                                    Long lastLatencyMs,
                                    String lastErrorCategory,
                                    String lastMessage,
                                    LocalDateTime lastProbeAt,
                                    LocalDateTime lastSuccessAt,
                                    LocalDateTime lastFailureAt,
                                    LocalDateTime nextProbeAt,
                                    LocalDateTime cooldownUntil,
                                    String overrideMode,
                                    String overrideReason,
                                    Long overrideOperatorId,
                                    String overrideOperatorName,
                                    LocalDateTime overrideUntil,
                                    Integer revision) {
}
