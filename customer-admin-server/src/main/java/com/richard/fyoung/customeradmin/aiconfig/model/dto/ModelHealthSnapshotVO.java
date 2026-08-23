package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.time.LocalDateTime;

/** 模型健康快照视图。 */
public record ModelHealthSnapshotVO(String healthStatus,
                                    String authStatus,
                                    String capabilityStatus,
                                    Integer consecutiveFailures,
                                    Long lastLatencyMs,
                                    String lastErrorCategory,
                                    String lastMessage,
                                    LocalDateTime lastProbeAt,
                                    LocalDateTime lastSuccessAt,
                                    LocalDateTime lastFailureAt,
                                    LocalDateTime nextProbeAt,
                                    Integer revision) {
}
