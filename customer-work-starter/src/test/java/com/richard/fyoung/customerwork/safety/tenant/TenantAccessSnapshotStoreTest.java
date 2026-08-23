package com.richard.fyoung.customerwork.safety.tenant;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantAccessSnapshotStoreTest {

    private final TenantAccessSnapshotStore store = new TenantAccessSnapshotStore();

    @Test
    void activeSnapshot_shouldAllowMatchingCredentialEpoch() {
        assertEquals(TenantAccessSnapshotStore.ApplyOutcome.APPLIED,
            store.apply("acme", snapshot("acme", "ACTIVE", 4L, null), 1_000L));

        TenantAccessDecision decision = store.evaluate("ACME", 4L, true, 1_500L, 5_000L);

        assertEquals(TenantAccessDecision.Kind.ALLOWED, decision.kind());
        assertEquals(4L, decision.accessEpoch());
    }

    @Test
    void olderSnapshot_shouldNotRollbackOrRefreshActiveSnapshot() {
        store.apply("acme", snapshot("acme", "ACTIVE", 4L, null), 1_000L);

        assertEquals(TenantAccessSnapshotStore.ApplyOutcome.IGNORED_OLDER,
            store.apply("acme", snapshot("acme", "SUSPENDED", 3L, null), 9_000L));
        assertEquals(TenantAccessDecision.Kind.SNAPSHOT_STALE,
            store.evaluate("acme", 4L, true, 10_000L, 5_000L).kind(),
            "旧快照不能伪造一次新鲜确认，否则 Nacos 回滚会长期 fail-open");
    }

    @Test
    void equalEpochConflict_shouldOnlyAllowMoreRestrictiveState() {
        store.apply("acme", snapshot("acme", "ACTIVE", 4L, null), 1_000L);

        assertEquals(TenantAccessSnapshotStore.ApplyOutcome.APPLIED_RESTRICTIVE,
            store.apply("acme", snapshot("acme", "SUSPENDED", 4L, null), 2_000L));
        assertEquals(TenantAccessDecision.Kind.ACCESS_DENIED,
            store.evaluate("acme", 4L, true, 2_100L, 5_000L).kind());
        assertEquals(TenantAccessSnapshotStore.ApplyOutcome.REJECTED_CONFLICT,
            store.apply("acme", snapshot("acme", "ACTIVE", 4L, null), 3_000L));
        assertEquals(TenantAccessDecision.Kind.ACCESS_DENIED,
            store.evaluate("acme", 4L, true, 3_100L, 5_000L).kind());
    }

    @Test
    void expiredTenantAndOldJwt_shouldFailClosedWithDistinctReasons() {
        store.apply("acme", snapshot("acme", "ACTIVE", 8L,
            LocalDateTime.now().minusMinutes(1).toString()), System.currentTimeMillis());
        assertEquals(TenantAccessDecision.Kind.ACCESS_DENIED,
            store.evaluate("acme", 8L, true, System.currentTimeMillis(), 5_000L).kind());

        TenantAccessSnapshotStore fresh = new TenantAccessSnapshotStore();
        fresh.apply("acme", snapshot("acme", "ACTIVE", 8L, null), System.currentTimeMillis());
        assertEquals(TenantAccessDecision.Kind.CREDENTIAL_REVOKED,
            fresh.evaluate("acme", 7L, true, System.currentTimeMillis(), 5_000L).kind());
        assertEquals(TenantAccessDecision.Kind.CREDENTIAL_REVOKED,
            fresh.evaluate("acme", null, true, System.currentTimeMillis(), 5_000L).kind());
    }

    @Test
    void invalidPayload_shouldNeverEnterStore() {
        TenantAccessSnapshot invalid = snapshot("other", "ACTIVE", 1L, null);

        assertEquals(TenantAccessSnapshotStore.ApplyOutcome.REJECTED,
            store.apply("acme", invalid, 1_000L));
        assertEquals(TenantAccessDecision.Kind.SNAPSHOT_UNAVAILABLE,
            store.evaluate("acme", null, false, 1_001L, 5_000L).kind());
    }

    @Test
    void malformedExpiry_shouldNeverEnterStore() {
        TenantAccessSnapshot invalid = snapshot("acme", "ACTIVE", 1L, "not-a-date");

        assertEquals(TenantAccessSnapshotStore.ApplyOutcome.REJECTED,
            store.apply("acme", invalid, 1_000L));
    }

    private TenantAccessSnapshot snapshot(String tenantId, String status, long epoch, String expireTime) {
        TenantAccessSnapshot snapshot = new TenantAccessSnapshot();
        snapshot.setSchemaVersion(TenantAccessConstants.SCHEMA_VERSION);
        snapshot.setTenantId(tenantId);
        snapshot.setStatus(status);
        snapshot.setAccessEpoch(epoch);
        snapshot.setExpireTime(expireTime);
        snapshot.setChangedAtMs(System.currentTimeMillis());
        return snapshot;
    }
}
