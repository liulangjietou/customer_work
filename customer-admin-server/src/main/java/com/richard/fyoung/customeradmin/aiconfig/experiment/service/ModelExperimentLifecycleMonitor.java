package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.experiment.config.ModelExperimentLifecycleProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 独立实验生命周期巡检，不开启 Admin 全局调度。 */
@Component
public class ModelExperimentLifecycleMonitor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ModelExperimentLifecycleMonitor.class);
    private static final long MIN_SCAN_INTERVAL_MS = 30000L;

    private final ModelExperimentLifecycleProperties properties;
    private final ModelExperimentLifecycleService lifecycleService;
    private ThreadPoolTaskScheduler scheduler;

    public ModelExperimentLifecycleMonitor(ModelExperimentLifecycleProperties properties,
                                           ModelExperimentLifecycleService lifecycleService) {
        this.properties = properties;
        this.lifecycleService = lifecycleService;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("model-experiment-lifecycle-");
        scheduler.initialize();
        long interval = Math.max(MIN_SCAN_INTERVAL_MS, properties.getScanIntervalMs());
        scheduler.scheduleWithFixedDelay(this::reconcileSafely, Duration.ofMillis(interval));
        log.info("model experiment lifecycle monitor started, intervalMs={}", interval);
    }

    void reconcileSafely() {
        try {
            for (ModelExperimentLifecycleService.LifecycleTarget target : lifecycleService.activeTargets()) {
                reconcileTarget(target);
            }
        } catch (Exception e) {
            log.error("model experiment lifecycle scan failed, code={}",
                "MODEL-EXPERIMENT-LIFECYCLE-FAILED", e);
        }
    }

    private void reconcileTarget(ModelExperimentLifecycleService.LifecycleTarget target) {
        try {
            TenantContext.runWith(target.tenantId(), () -> lifecycleService.reconcile(target.experimentId()));
        } catch (Exception e) {
            log.error("model experiment lifecycle reconcile failed, code={}, experimentId={}",
                "MODEL-EXPERIMENT-RECONCILE-FAILED", target.experimentId(), e);
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
