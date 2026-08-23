package com.richard.fyoung.customerwork.safety.tenant;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时租户访问快照的进程内权威视图。
 *
 * <p>写入只接受单调递增 epoch。同 epoch 冲突时只能从可用收紧为不可用，绝不从不可用放宽为可用；
 * 旧快照也不会刷新确认时间，因此 Nacos 长期回滚到旧版本时最终会触发 stale fail-closed。</p>
 */
@Component
public class TenantAccessSnapshotStore {

    private final ConcurrentMap<String, ConfirmedSnapshot> snapshots = new ConcurrentHashMap<>();

    public ApplyOutcome apply(String expectedTenantId, TenantAccessSnapshot incoming, long confirmedAtMs) {
        if (!isValid(expectedTenantId, incoming)) {
            return ApplyOutcome.REJECTED;
        }
        String key = TenantContext.normalizedTenantKey(expectedTenantId);
        AtomicReference<ApplyOutcome> outcome = new AtomicReference<>(ApplyOutcome.REJECTED);
        snapshots.compute(key, (ignored, current) -> merge(current, incoming, confirmedAtMs, outcome));
        return outcome.get();
    }

    public TenantAccessDecision evaluate(String tenantId, Long expectedEpoch, boolean requireEpoch,
                                         long nowMs, long maxStalenessMs) {
        ConfirmedSnapshot confirmed = snapshots.get(TenantContext.normalizedTenantKey(tenantId));
        if (confirmed == null) {
            return TenantAccessDecision.unavailable();
        }
        TenantAccessSnapshot snapshot = confirmed.snapshot();
        long epoch = snapshot.getAccessEpoch();
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
        if (!allowsAccess(snapshot, now)) {
            return new TenantAccessDecision(TenantAccessDecision.Kind.ACCESS_DENIED, epoch);
        }
        if (maxStalenessMs > 0 && nowMs - confirmed.confirmedAtMs() > maxStalenessMs) {
            return new TenantAccessDecision(TenantAccessDecision.Kind.SNAPSHOT_STALE, epoch);
        }
        if ((requireEpoch && expectedEpoch == null)
            || (expectedEpoch != null && expectedEpoch.longValue() != epoch)) {
            return new TenantAccessDecision(TenantAccessDecision.Kind.CREDENTIAL_REVOKED, epoch);
        }
        return TenantAccessDecision.allowed(epoch);
    }

    public TenantAccessSnapshot current(String tenantId) {
        ConfirmedSnapshot confirmed = snapshots.get(TenantContext.normalizedTenantKey(tenantId));
        return confirmed == null ? null : confirmed.snapshot();
    }

    private ConfirmedSnapshot merge(ConfirmedSnapshot current, TenantAccessSnapshot incoming,
                                    long confirmedAtMs, AtomicReference<ApplyOutcome> outcome) {
        if (current == null || incoming.getAccessEpoch() > current.snapshot().getAccessEpoch()) {
            outcome.set(ApplyOutcome.APPLIED);
            return new ConfirmedSnapshot(incoming, confirmedAtMs);
        }
        if (incoming.getAccessEpoch() < current.snapshot().getAccessEpoch()) {
            outcome.set(ApplyOutcome.IGNORED_OLDER);
            return current;
        }
        if (sameState(current.snapshot(), incoming)) {
            outcome.set(ApplyOutcome.CONFIRMED);
            return new ConfirmedSnapshot(current.snapshot(), confirmedAtMs);
        }
        if (allowsAccess(current.snapshot(), LocalDateTime.now()) && !allowsAccess(incoming, LocalDateTime.now())) {
            outcome.set(ApplyOutcome.APPLIED_RESTRICTIVE);
            return new ConfirmedSnapshot(incoming, confirmedAtMs);
        }
        outcome.set(ApplyOutcome.REJECTED_CONFLICT);
        return current;
    }

    private boolean isValid(String expectedTenantId, TenantAccessSnapshot snapshot) {
        if (snapshot == null || snapshot.getSchemaVersion() == null
            || snapshot.getSchemaVersion() != TenantAccessConstants.SCHEMA_VERSION
            || !TenantContext.isValidTenantId(expectedTenantId)
            || !TenantContext.isValidTenantId(snapshot.getTenantId())
            || !TenantContext.sameTenant(expectedTenantId, snapshot.getTenantId())
            || snapshot.getAccessEpoch() == null || snapshot.getAccessEpoch() < 0
            || !validStatus(snapshot.getStatus())) {
            return false;
        }
        return validExpireTime(snapshot.getExpireTime());
    }

    private boolean validStatus(String status) {
        return TenantAccessConstants.STATUS_ACTIVE.equals(status)
            || TenantAccessConstants.STATUS_SUSPENDED.equals(status)
            || TenantAccessConstants.STATUS_TERMINATED.equals(status);
    }

    private boolean allowsAccess(TenantAccessSnapshot snapshot, LocalDateTime now) {
        if (!TenantAccessConstants.STATUS_ACTIVE.equals(snapshot.getStatus())) {
            return false;
        }
        LocalDateTime expireTime = parseExpireTime(snapshot.getExpireTime());
        return expireTime == null || expireTime.isAfter(now);
    }

    private boolean sameState(TenantAccessSnapshot left, TenantAccessSnapshot right) {
        return TenantContext.sameTenant(left.getTenantId(), right.getTenantId())
            && Objects.equals(left.getStatus(), right.getStatus())
            && Objects.equals(normalizeExpireTime(left.getExpireTime()), normalizeExpireTime(right.getExpireTime()));
    }

    private String normalizeExpireTime(String expireTime) {
        return StringUtils.hasText(expireTime) ? expireTime.trim() : null;
    }

    private LocalDateTime parseExpireTime(String expireTime) {
        if (!StringUtils.hasText(expireTime)) {
            return null;
        }
        return LocalDateTime.parse(expireTime.trim());
    }

    private boolean validExpireTime(String expireTime) {
        try {
            parseExpireTime(expireTime);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private record ConfirmedSnapshot(TenantAccessSnapshot snapshot, long confirmedAtMs) {
    }

    public enum ApplyOutcome {
        APPLIED,
        APPLIED_RESTRICTIVE,
        CONFIRMED,
        IGNORED_OLDER,
        REJECTED_CONFLICT,
        REJECTED
    }
}
