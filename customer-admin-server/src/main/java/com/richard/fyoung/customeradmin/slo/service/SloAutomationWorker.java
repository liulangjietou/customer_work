package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.slo.config.SloAutomationProperties;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.entity.SloNotificationTask;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 独立双循环 Worker，不启用 Admin 全局调度；评估与通知均由数据库租约协调多副本。 */
@Component
public class SloAutomationWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SloAutomationWorker.class);
    private static final long MIN_SCAN_INTERVAL_MS = 1000L;

    private final SloAutomationProperties properties;
    private final SloEvaluationLeaseService leaseService;
    private final SloEvaluationService evaluationService;
    private final SloNotificationService notificationService;
    private ThreadPoolTaskScheduler scheduler;

    public SloAutomationWorker(SloAutomationProperties properties,
                               SloEvaluationLeaseService leaseService,
                               SloEvaluationService evaluationService,
                               SloNotificationService notificationService) {
        this.properties = properties;
        this.leaseService = leaseService;
        this.evaluationService = evaluationService;
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("slo-automation-");
        scheduler.initialize();
        long evaluationScan = Math.max(MIN_SCAN_INTERVAL_MS, properties.getScanIntervalMs());
        long notificationScan = Math.max(MIN_SCAN_INTERVAL_MS, properties.getNotificationScanIntervalMs());
        scheduler.scheduleWithFixedDelay(this::evaluateSafely, Duration.ofMillis(evaluationScan));
        scheduler.scheduleWithFixedDelay(this::notifySafely, Duration.ofMillis(notificationScan));
        log.info("SLO automation worker started, evaluationScanMs={}, notificationScanMs={}",
            evaluationScan, notificationScan);
    }

    void evaluateSafely() {
        try {
            for (SloPolicy policy : leaseService.claimDue()) {
                TenantContext.runWith(policy.getTenantId(), () -> evaluate(policy));
            }
        } catch (Exception e) {
            log.error("SLO evaluation scan failed, code={}", "SLO-EVALUATION-SCAN-FAIL", e);
        }
    }

    void notifySafely() {
        try {
            for (SloNotificationTask task : notificationService.claimDue()) {
                TenantContext.runWith(task.getTenantId(), () -> deliver(task));
            }
        } catch (Exception e) {
            log.error("SLO notification scan failed, code={}", "SLO-NOTIFICATION-SCAN-FAIL", e);
        }
    }

    private void evaluate(SloPolicy policy) {
        try {
            SloEvaluationVO result = evaluationService.evaluateForTenant(policy.getId(), policy.getTenantId());
            leaseService.complete(policy, result);
        } catch (Exception e) {
            try {
                leaseService.fail(policy, e);
            } catch (Exception stateFailure) {
                log.error("SLO evaluation failure state persistence failed, code={}, policyId={}",
                    "SLO-EVALUATION-STATE-FAIL", policy.getId(), stateFailure);
            }
            log.error("SLO scheduled evaluation failed, code={}, policyId={}, tenantId={}",
                "SLO-SCHEDULED-EVALUATION-FAIL", policy.getId(), policy.getTenantId(), e);
        }
    }

    private void deliver(SloNotificationTask task) {
        try {
            notificationService.deliver(task);
        } catch (Exception e) {
            try {
                notificationService.markFailed(task, e);
            } catch (Exception stateFailure) {
                log.error("SLO notification failure state persistence failed, code={}, taskId={}",
                    "SLO-NOTIFICATION-STATE-FAIL", task.getId(), stateFailure);
            }
            log.error("SLO notification delivery failed, code={}, taskId={}, tenantId={}",
                "SLO-NOTIFICATION-DELIVERY-FAIL", task.getId(), task.getTenantId(), e);
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
