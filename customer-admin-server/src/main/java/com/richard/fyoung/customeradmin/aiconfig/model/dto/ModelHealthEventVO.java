package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.time.LocalDateTime;

/** 健康事件视图。 */
public record ModelHealthEventVO(Long id,
                                 String source,
                                 String probeKind,
                                 String healthStatus,
                                 Integer testStatus,
                                 Long latencyMs,
                                 String errorCategory,
                                 String message,
                                 LocalDateTime occurredAt) {
}
