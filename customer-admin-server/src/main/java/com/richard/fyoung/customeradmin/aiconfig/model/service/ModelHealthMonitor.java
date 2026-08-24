package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthMonitorProperties;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthSnapshotMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 模型持续健康巡检驱动。独立调度，不开启 admin 全局 {@code @EnableScheduling}；
 * 每次只领取到期的一小批部署，实际探测复用 {@link ModelHealthService} 的权限边界、分类和事件存储。
 */
@Component
public class ModelHealthMonitor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthMonitor.class);
    private static final long MIN_SCAN_INTERVAL_MS = 30000L;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 100;

    private final ModelHealthMonitorProperties properties;
    private final AiModelHealthSnapshotMapper snapshotMapper;
    private final ModelHealthService healthService;
    private ThreadPoolTaskScheduler scheduler;

    public ModelHealthMonitor(ModelHealthMonitorProperties properties,
                              AiModelHealthSnapshotMapper snapshotMapper,
                              ModelHealthService healthService) {
        this.properties = properties;
        this.snapshotMapper = snapshotMapper;
        this.healthService = healthService;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("model-health-monitor-");
        scheduler.initialize();
        long intervalMs = Math.max(MIN_SCAN_INTERVAL_MS, properties.getScanIntervalMs());
        scheduler.scheduleWithFixedDelay(this::dispatchSafely, Duration.ofMillis(intervalMs));
        log.info("model health monitor started, intervalMs={}, batchSize={}",
            intervalMs, effectiveBatchSize());
    }

    void dispatchSafely() {
        try {
            healthService.expireOverrides(effectiveBatchSize());
            List<AiModelConfig> models = CrossTenantOperations.execute(
                () -> snapshotMapper.findDueModels(effectiveBatchSize()));
            for (AiModelConfig model : models) {
                TenantContext.runWith(model.getTenantId(), () -> probe(model));
            }
        } catch (Exception e) {
            log.error("model health monitor scan failed, code={}", "MODEL-HEALTH-SCAN-FAILED", e);
        }
    }

    private void probe(AiModelConfig model) {
        try {
            healthService.probe(model.getId(), ModelProbeSource.SCHEDULED)
                .exceptionally(error -> {
                    log.error("scheduled model health probe failed, code={}, modelId={}",
                        "MODEL-HEALTH-SCHEDULED-FAILED", model.getId());
                    return null;
                });
        } catch (Exception e) {
            log.error("scheduled model health probe dispatch failed, code={}, modelId={}",
                "MODEL-HEALTH-DISPATCH-FAILED", model.getId());
        }
    }

    private int effectiveBatchSize() {
        return Math.max(MIN_BATCH_SIZE, Math.min(properties.getBatchSize(), MAX_BATCH_SIZE));
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
