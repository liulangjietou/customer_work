package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customeradmin.slo.config.SloAutomationProperties;
import com.richard.fyoung.customeradmin.slo.domain.SloNotificationTaskStatus;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import com.richard.fyoung.customeradmin.slo.entity.SloAlertEvent;
import com.richard.fyoung.customeradmin.slo.entity.SloNotificationTask;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloNotificationTaskMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SLO 告警事件的持久任务、租约领取、重试与站内消息投递。 */
@Service
public class SloNotificationService {

    private static final String BIZ_TYPE = "SLO_ALERT_EVENT";
    private static final String MESSAGE_LINK = "/system/slo";
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int MAX_BACKOFF_SHIFT = 10;
    private static final long MIN_LEASE_MS = 1000L;

    private final SloNotificationTaskMapper taskMapper;
    private final SiteMessageService siteMessageService;
    private final SloAutomationProperties properties;
    private final Clock clock;
    private final String workerId;

    @Autowired
    public SloNotificationService(SloNotificationTaskMapper taskMapper,
                                  SiteMessageService siteMessageService,
                                  SloAutomationProperties properties) {
        this(taskMapper, siteMessageService, properties, Clock.systemUTC(),
            ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID());
    }

    SloNotificationService(SloNotificationTaskMapper taskMapper,
                           SiteMessageService siteMessageService,
                           SloAutomationProperties properties,
                           Clock clock,
                           String workerId) {
        this.taskMapper = taskMapper;
        this.siteMessageService = siteMessageService;
        this.properties = properties;
        this.clock = clock;
        this.workerId = workerId;
    }

    /** 必须和告警事件在同一事务中调用，保证状态变化与通知任务同提交。 */
    void enqueue(SloAlertEvent event, SloAlert alert, SloPolicy policy) {
        long now = clock.millis();
        SloNotificationTask task = new SloNotificationTask();
        task.setId(UUID.randomUUID().toString());
        task.setTenantId(event.getTenantId());
        task.setEventId(event.getId());
        task.setAlertId(alert.getId());
        task.setPolicyId(policy.getId());
        task.setEventType(event.getEventType());
        task.setTitle(title(event.getEventType()));
        task.setContent(content(event, policy));
        task.setStatus(SloNotificationTaskStatus.PENDING.name());
        task.setAttempts(0);
        task.setNextAttemptAtMs(now);
        task.setLeaseUntilMs(0L);
        task.setRecipientCount(0);
        task.setCreatedAtMs(now);
        task.setUpdatedAtMs(now);
        taskMapper.insertIgnore(task);
    }

    /** 系统级治理扫描：显式跨租户读取，再逐条 CAS 领取。 */
    public List<SloNotificationTask> claimDue() {
        long now = clock.millis();
        int limit = Math.max(1, Math.min(properties.getNotificationBatchSize(), 200));
        long leaseMs = Math.max(MIN_LEASE_MS, properties.getNotificationLeaseMs());
        List<SloNotificationTask> candidates = CrossTenantOperations.execute(
            () -> taskMapper.findDueCandidates(now, limit));
        List<SloNotificationTask> claimed = new ArrayList<>();
        for (SloNotificationTask candidate : candidates) {
            int changed = CrossTenantOperations.execute(() -> taskMapper.claim(
                candidate.getId(), workerId, now, now + leaseMs));
            if (changed == 1) {
                candidate.setStatus(SloNotificationTaskStatus.PROCESSING.name());
                candidate.setLeaseOwner(workerId);
                candidate.setLeaseUntilMs(now + leaseMs);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    /**
     * 持有任务行锁投递并在同一数据库事务内写站内消息和完成状态。
     * 即使租约跨过期点，新的领取者也只能在本事务提交后看到 DELIVERED，避免重复消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deliver(SloNotificationTask claimed) {
        long now = clock.millis();
        SloNotificationTask owned = taskMapper.lockOwned(claimed.getId(), workerId, now);
        if (owned == null) {
            throw new IllegalStateException("SLO notification lease lost: " + claimed.getId());
        }
        List<Long> userIds = taskMapper.findSloViewUserIds(owned.getTenantId());
        for (Long userId : userIds) {
            siteMessageService.send(userId, owned.getTitle(), owned.getContent(), BIZ_TYPE,
                String.valueOf(owned.getEventId()), MESSAGE_LINK);
        }
        if (taskMapper.markDelivered(owned.getId(), workerId, userIds.size(), clock.millis()) != 1) {
            throw new IllegalStateException("SLO notification completion lease lost: " + owned.getId());
        }
    }

    public void markFailed(SloNotificationTask task, Throwable failure) {
        int attempts = task.getAttempts() + 1;
        long now = clock.millis();
        long base = Math.max(1L, properties.getNotificationBaseBackoffMs());
        long shifted = base * (1L << Math.min(attempts, MAX_BACKOFF_SHIFT));
        long backoff = Math.min(shifted, Math.max(base, properties.getNotificationMaxBackoffMs()));
        String error = errorMessage(failure);
        CrossTenantOperations.execute(() -> taskMapper.markFailed(
            task.getId(), workerId, attempts, now + backoff, error, now));
    }

    private String title(String eventType) {
        return switch (eventType) {
            case "OPENED" -> "SLO 错误预算告警";
            case "ACKED" -> "SLO 告警已确认";
            case "RESOLVED" -> "SLO 告警已恢复";
            default -> "SLO 告警状态变化";
        };
    }

    private String content(SloAlertEvent event, SloPolicy policy) {
        return String.format("策略 %s（%s%s）发生 %s，短窗燃烧率 %s，长窗燃烧率 %s。",
            policy.getPolicyName(), policy.getScopeType(),
            policy.getScopeKey() == null ? "" : ":" + policy.getScopeKey(),
            event.getEventType(), event.getShortBurnRate().toPlainString(),
            event.getLongBurnRate().toPlainString());
    }

    private String errorMessage(Throwable failure) {
        String message = failure.getMessage() == null
            ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
