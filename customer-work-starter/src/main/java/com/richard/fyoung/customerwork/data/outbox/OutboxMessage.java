package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.Getter;

import java.util.UUID;

/** 数据库 Outbox 消息（充血模型）：自带成功、失败退避与租约释放状态流转。 */
@Getter
public class OutboxMessage {

    private static final int MAX_BACKOFF_SHIFT = 10;

    private final String id;
    private final String tenantId;
    private final String type;
    private final String aggregateId;
    private final String payload;
    private final long createdAtMs;

    private OutboxStatus status;
    private int attempts;
    private long nextAttemptAtMs;
    private String leaseOwner;
    private long leaseUntilMs;
    private String lastError;
    private long finishedAtMs;

    public OutboxMessage(String id, String type, String aggregateId, String payload, long createdAtMs) {
        this(id, currentTenant(), type, aggregateId, payload, createdAtMs);
    }

    public OutboxMessage(String id, String tenantId, String type, String aggregateId,
                         String payload, long createdAtMs) {
        this.id = id;
        this.tenantId = tenantId;
        this.type = type;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.createdAtMs = createdAtMs;
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAtMs = createdAtMs;
    }

    public static OutboxMessage create(String type, String aggregateId, String payload) {
        return new OutboxMessage(UUID.randomUUID().toString(), currentTenant(), type, aggregateId, payload,
            System.currentTimeMillis());
    }

    private static String currentTenant() {
        return TenantContext.isPresent() ? TenantContext.get() : TenantContext.DEFAULT;
    }

    public void succeed(long nowMs) {
        status = OutboxStatus.SUCCEEDED;
        finishedAtMs = nowMs;
        clearLease();
    }

    public void fail(String error, int maxAttempts, long baseBackoffMs, long nowMs) {
        attempts++;
        lastError = error;
        if (attempts >= maxAttempts) {
            status = OutboxStatus.ABANDONED;
            finishedAtMs = nowMs;
        } else {
            status = OutboxStatus.PENDING;
            int shift = Math.min(attempts, MAX_BACKOFF_SHIFT);
            nextAttemptAtMs = nowMs + baseBackoffMs * (1L << shift);
        }
        clearLease();
    }

    /** 没有 Handler 是部署配置错误，不消耗业务重试次数。 */
    public void releaseWithoutAttempt(long nextAttemptAtMs) {
        status = OutboxStatus.PENDING;
        this.nextAttemptAtMs = nextAttemptAtMs;
        clearLease();
    }

    public void restore(OutboxStatus status, int attempts, long nextAttemptAtMs,
                        String leaseOwner, long leaseUntilMs, String lastError, long finishedAtMs) {
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAtMs = nextAttemptAtMs;
        this.leaseOwner = leaseOwner;
        this.leaseUntilMs = leaseUntilMs;
        this.lastError = lastError;
        this.finishedAtMs = finishedAtMs;
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntilMs = 0L;
    }
}
