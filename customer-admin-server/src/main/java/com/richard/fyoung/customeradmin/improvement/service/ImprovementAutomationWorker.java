package com.richard.fyoung.customeradmin.improvement.service;

import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 独立单循环 Worker；不依赖 Admin 全局调度开关，所有工作由数据库租约协调。 */
@Component
public class ImprovementAutomationWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImprovementAutomationWorker.class);
    private static final long MIN_SCAN_INTERVAL_MS = 1000L;

    private final ImprovementAutomationProperties properties;
    private final ImprovementAutomationLeaseService leaseService;
    private final ImprovementCaseService caseService;
    private ThreadPoolTaskScheduler scheduler;

    public ImprovementAutomationWorker(ImprovementAutomationProperties properties,
                                       ImprovementAutomationLeaseService leaseService,
                                       ImprovementCaseService caseService) {
        this.properties = properties;
        this.leaseService = leaseService;
        this.caseService = caseService;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("improvement-observer-");
        scheduler.initialize();
        long scanMs = Math.max(MIN_SCAN_INTERVAL_MS, properties.getScanIntervalMs());
        scheduler.scheduleWithFixedDelay(this::scanSafely, Duration.ofMillis(scanMs));
        log.info("improvement automation worker started, scanMs={}", scanMs);
    }

    void scanSafely() {
        try {
            for (AgentImprovementCase improvementCase : leaseService.claimDue()) {
                TenantContext.runWith(improvementCase.getTenantId(), () -> process(improvementCase));
            }
        } catch (Exception e) {
            log.error("improvement automation scan failed, code={}",
                "IMPROVEMENT-AUTOMATION-SCAN-FAIL", e);
        }
    }

    private void process(AgentImprovementCase improvementCase) {
        try {
            caseService.processAutomation(improvementCase);
        } catch (Exception e) {
            try {
                caseService.markAutomationFailure(improvementCase, e);
            } catch (Exception stateFailure) {
                log.error("improvement automation state persistence failed, code={}, caseId={}",
                    "IMPROVEMENT-AUTOMATION-STATE-FAIL", improvementCase.getId(), stateFailure);
            }
            log.error("improvement automation processing failed, code={}, caseId={}, tenantId={}",
                "IMPROVEMENT-AUTOMATION-PROCESS-FAIL", improvementCase.getId(),
                improvementCase.getTenantId(), e);
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
