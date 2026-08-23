package com.richard.fyoung.customeradmin.aiconfig.experiment.dto;

import java.time.LocalDateTime;

/** 追加式实验生命周期事件视图。 */
public record ModelExperimentEventVO(
    Long id,
    String eventType,
    String fromStatus,
    String toStatus,
    String reason,
    Long actorId,
    LocalDateTime occurredAt
) {
}
