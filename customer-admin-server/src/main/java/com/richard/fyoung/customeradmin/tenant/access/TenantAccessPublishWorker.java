package com.richard.fyoung.customeradmin.tenant.access;

import com.richard.fyoung.customeradmin.tenant.access.entity.TenantAccessPublishTask;
import com.richard.fyoung.customeradmin.tenant.access.service.TenantAccessPublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 独立租约 Worker；不依赖 admin 全局调度开关。 */
@Component
public class TenantAccessPublishWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TenantAccessPublishWorker.class);

    private final TenantAccessPublishProperties properties;
    private final TenantAccessPublishTaskService taskService;
    private final TenantAccessPublisher publisher;
    private final RuntimePublishProperties runtimePublishProperties;
    private ThreadPoolTaskScheduler scheduler;

    public TenantAccessPublishWorker(TenantAccessPublishProperties properties,
                                     RuntimePublishProperties runtimePublishProperties,
                                     TenantAccessPublishTaskService taskService,
                                     TenantAccessPublisher publisher) {
        this.properties = properties;
        this.runtimePublishProperties = runtimePublishProperties;
        this.taskService = taskService;
        this.publisher = publisher;
    }

    @PostConstruct
    public void start() {
        if (!properties.deliveryEnabled(runtimePublishProperties)) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("tenant-access-publish-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::dispatchSafely,
            Duration.ofMillis(properties.getScanIntervalMs()));
        log.info("tenant access publish worker started, scanIntervalMs={}", properties.getScanIntervalMs());
    }

    void dispatchSafely() {
        try {
            List<TenantAccessPublishTask> tasks = taskService.claimDue();
            for (TenantAccessPublishTask task : tasks) {
                TenantContext.runWith(task.getTenantId(), () -> process(task));
            }
        } catch (Exception e) {
            log.error("tenant access publish scan failed, code={}", "TENANT-ACCESS-PUBLISH-SCAN-FAIL", e);
        }
    }

    private void process(TenantAccessPublishTask task) {
        try {
            publisher.publish(task);
            taskService.markPublished(task);
        } catch (Exception e) {
            try {
                taskService.markDeliveryFailed(task, e);
            } catch (Exception stateFailure) {
                log.error("tenant access publish state persistence failed, code={}, taskId={}",
                    "TENANT-ACCESS-PUBLISH-STATE-FAIL", task.getId(), stateFailure);
            }
            log.error("tenant access publish task failed, code={}, taskId={}, tenantId={}",
                "TENANT-ACCESS-PUBLISH-TASK-FAIL", task.getId(), task.getTenantId(), e);
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
