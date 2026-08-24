package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 健康快照/事件与可靠运行时发布任务的同事务协调边界。 */
@Component
public class ModelHealthCoordinator {

    private final ModelHealthStore healthStore;
    private final ModelHealthRouteRefreshService routeRefreshService;

    public ModelHealthCoordinator(ModelHealthStore healthStore,
                                  ModelHealthRouteRefreshService routeRefreshService) {
        this.healthStore = healthStore;
        this.routeRefreshService = routeRefreshService;
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelHealthStore.RecordResult record(AiModelConfig model, ModelTestResult result,
                                                ModelProbeSource source) {
        ModelHealthStore.RecordResult recorded = healthStore.record(model, result, source);
        refreshIfRequired(model, recorded);
        return recorded;
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelHealthStore.RecordResult override(AiModelConfig model,
                                                  ModelHealthOverrideRequest request,
                                                  Long operatorId,
                                                  String operatorName) {
        ModelHealthStore.RecordResult recorded = healthStore.override(
            model, request, operatorId, operatorName);
        refreshIfRequired(model, recorded);
        return recorded;
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelHealthStore.RecordResult expireOverride(AiModelConfig model, LocalDateTime now) {
        ModelHealthStore.RecordResult recorded = healthStore.expireOverride(model, now);
        refreshIfRequired(model, recorded);
        return recorded;
    }

    private void refreshIfRequired(AiModelConfig model, ModelHealthStore.RecordResult recorded) {
        if (recorded.routingChanged()) {
            routeRefreshService.refresh(model);
        }
    }
}
