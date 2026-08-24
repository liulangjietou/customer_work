package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher.PreparedRuntimeConfig;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateDecision;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalReleaseGateService;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 独立轮询发布 worker：不依赖 admin 全局 {@code @EnableScheduling}。 */
@Component
public class RuntimePublishWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RuntimePublishWorker.class);

    private final RuntimePublishProperties properties;
    private final RuntimePublishTaskService taskService;
    private final CustomerWorkConfigPublisher publisher;
    private final EvalReleaseGateService gateService;
    private ThreadPoolTaskScheduler scheduler;

    @Autowired
    public RuntimePublishWorker(RuntimePublishProperties properties, RuntimePublishTaskService taskService,
                                CustomerWorkConfigPublisher publisher,
                                EvalReleaseGateService gateService) {
        this.properties = properties;
        this.taskService = taskService;
        this.publisher = publisher;
        this.gateService = gateService;
    }

    /** 兼容不装配门禁服务的独立单测。 */
    RuntimePublishWorker(RuntimePublishProperties properties, RuntimePublishTaskService taskService,
                         CustomerWorkConfigPublisher publisher) {
        this.properties = properties;
        this.taskService = taskService;
        this.publisher = publisher;
        this.gateService = null;
    }

    @PostConstruct
    public void start() {
        if (!properties.getNacos().isEnabled()) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("runtime-publish-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::dispatchSafely,
            Duration.ofMillis(properties.getScanIntervalMs()));
        log.info("runtime publish worker started, scanIntervalMs={}", properties.getScanIntervalMs());
    }

    void dispatchSafely() {
        try {
            List<RuntimePublishTask> tasks = taskService.claimDue();
            for (RuntimePublishTask task : tasks) {
                TenantContext.runWith(task.getTenantId(), () -> process(task));
            }
        } catch (Exception e) {
            log.error("runtime publish scan failed, code={}", "RUNTIME-PUBLISH-SCAN-FAIL", e);
        }
    }

    private void process(RuntimePublishTask task) {
        try {
            PreparedRuntimeConfig prepared = publisher.prepareTask(task);
            if (task.getContentHash() == null) {
                attachMetadata(task, prepared);
                taskService.attachMetadata(task);
            } else if (!Objects.equals(task.getContentHash(), prepared.contentHash())) {
                taskService.markContentChangedTerminal(task);
                log.info("runtime publish task stopped after deterministic content change, taskId={}, agentId={}",
                    task.getId(), task.getTargetId());
                return;
            }
            // V71 上线前的历史任务 publish_intent 可能为空，按 NORMAL 处理。
            RuntimePublishIntent intent = task.getPublishIntent() == null
                ? RuntimePublishIntent.NORMAL : RuntimePublishIntent.valueOf(task.getPublishIntent());
            if (gateService != null && !intent.bypassesEvalGate()) {
                EvalGateDecision decision = gateService.evaluateAndRecord(task, prepared);
                if (!decision.allowsPublish()) {
                    log.info("runtime publish task blocked by eval gate, taskId={}, agentId={}, reason={}",
                        task.getId(), task.getTargetId(), decision.summary());
                    return;
                }
            }
            taskService.publishWithFencing(task, () -> publisher.publishPrepared(task, prepared));
        } catch (RuntimePublishLeaseLostException e) {
            // 租约已由其它 Worker 接管，不得把旧 Worker 的失败覆盖到新租约状态。
            log.info("runtime publish task stopped after lease loss, taskId={}, agentId={}",
                task.getId(), task.getTargetId());
        } catch (Exception e) {
            try {
                taskService.markDeliveryFailed(task, e);
            } catch (Exception stateFailure) {
                log.error("runtime publish failure state persistence failed, code={}, taskId={}",
                    "RUNTIME-PUBLISH-STATE-FAIL", task.getId(), stateFailure);
            }
            log.error("runtime publish task failed, code={}, taskId={}, agentId={}",
                "RUNTIME-PUBLISH-TASK-FAIL", task.getId(), task.getTargetId(), e);
        }
    }

    private void attachMetadata(RuntimePublishTask task, PreparedRuntimeConfig prepared) {
        task.setTargetCode(prepared.targetCode());
        task.setChannelCode(prepared.channelCode());
        task.setDataId(prepared.dataId());
        task.setGroupName(prepared.groupName());
        task.setRevision(prepared.revision());
        task.setContentHash(prepared.contentHash());
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
