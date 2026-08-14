package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStatus;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 可靠投递队列健康检查：暴露积压与放弃数量，但不因单个下游故障把所有业务 Pod 摘掉。
 *
 * <p>出现已放弃消息或积压超过阈值时返回 DEGRADED（HTTP 仍为 200），数据库查询失败才返回 DOWN。</p>
 */
public class DeliveryQueueHealthIndicator implements HealthIndicator {

    private final OutboxStore outboxStore;
    private final DeadLetterStore deadLetterStore;
    private final CustomerWorkProperties properties;

    public DeliveryQueueHealthIndicator(OutboxStore outboxStore, DeadLetterStore deadLetterStore,
                                        CustomerWorkProperties properties) {
        this.outboxStore = outboxStore;
        this.deadLetterStore = deadLetterStore;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            return CrossTenantOperations.execute(this::readHealth);
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }

    private Health readHealth() {
        long outboxPending = outboxStore.count(OutboxStatus.PENDING);
        long outboxProcessing = outboxStore.count(OutboxStatus.PROCESSING);
        long outboxAbandoned = outboxStore.count(OutboxStatus.ABANDONED);
        long deadLetterPending = deadLetterStore == null ? 0L
            : deadLetterStore.count(DeadLetterStatus.PENDING);
        long deadLetterProcessing = deadLetterStore == null ? 0L
            : deadLetterStore.count(DeadLetterStatus.PROCESSING);
        long deadLetterAbandoned = deadLetterStore == null ? 0L
            : deadLetterStore.count(DeadLetterStatus.ABANDONED);
        boolean degraded = outboxAbandoned > 0 || deadLetterAbandoned > 0
            || outboxPending > properties.getOutbox().getDegradedPendingThreshold()
            || deadLetterPending > properties.getDeadLetter().getDegradedPendingThreshold();
        Health.Builder builder = degraded ? Health.status("DEGRADED") : Health.up();
        return builder
            .withDetail("outbox.pending", outboxPending)
            .withDetail("outbox.processing", outboxProcessing)
            .withDetail("outbox.abandoned", outboxAbandoned)
            .withDetail("deadletter.pending", deadLetterPending)
            .withDetail("deadletter.processing", deadLetterProcessing)
            .withDetail("deadletter.abandoned", deadLetterAbandoned)
            .build();
    }
}
