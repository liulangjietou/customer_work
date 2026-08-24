package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 把权威健康快照投影成不含探测明细和凭据的运行时动态路由 overlay。 */
@Component
public class ModelHealthRuntimeAccess {

    private final ModelHealthStore healthStore;

    public ModelHealthRuntimeAccess(ModelHealthStore healthStore) {
        this.healthStore = healthStore;
    }

    public CustomerWorkRuntimeConfig.HealthOverlay overlay(AiModelConfig model) {
        ModelHealthSnapshotVO snapshot = healthStore.get(model);
        CustomerWorkRuntimeConfig.HealthOverlay overlay = new CustomerWorkRuntimeConfig.HealthOverlay();
        overlay.setHealthStatus(snapshot.healthStatus());
        overlay.setEffectiveHealthStatus(snapshot.effectiveHealthStatus());
        overlay.setRoutingAvailable(snapshot.routingAvailable());
        overlay.setOverrideMode(snapshot.overrideMode());
        overlay.setRevision(snapshot.revision());
        overlay.setCooldownUntilEpochMs(toEpochMs(snapshot.cooldownUntil()));
        overlay.setOverrideUntilEpochMs(toEpochMs(snapshot.overrideUntil()));
        return overlay;
    }

    private Long toEpochMs(LocalDateTime value) {
        return value == null ? null
            : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
