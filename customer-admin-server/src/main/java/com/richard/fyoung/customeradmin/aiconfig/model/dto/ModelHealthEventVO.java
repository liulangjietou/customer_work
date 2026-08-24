package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.time.LocalDateTime;

/** 健康事件视图。 */
public record ModelHealthEventVO(Long id,
                                 String eventType,
                                 String source,
                                 String probeKind,
                                 String previousHealthStatus,
                                 String healthStatus,
                                 String effectiveHealthStatus,
                                 String overrideMode,
                                 Long operatorId,
                                 String operatorName,
                                 Integer testStatus,
                                 Long latencyMs,
                                 String errorCategory,
                                 String message,
                                 LocalDateTime occurredAt) {
}
