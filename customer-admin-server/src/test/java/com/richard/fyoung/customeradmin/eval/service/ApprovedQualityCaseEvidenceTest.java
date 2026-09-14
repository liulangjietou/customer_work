package com.richard.fyoung.customeradmin.eval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.eval.config.EvalGateway;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetRelease;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReleaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReviewStatus;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshotStore;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovedQualityCaseEvidenceTest {
    private static final String CASES = "[{\"id\":\"target\",\"input\":\"问题\",\"expected\":\"要点\"}]";

    @Test
    void targetMustBeInTheApprovedSnapshotNotMerelyInTheWorkingSet() {
        var snapshot = snapshot(CASES, 1);
        var service = service(snapshot, release(snapshot, EvalDatasetReviewStatus.APPROVED, snapshot.contentHash()));
        assertEquals(snapshot, service.requireApprovedQualityCase("release", "target"));
        assertThrows(BizException.class, () -> service.requireApprovedQualityCase("release", "omitted"));
    }

    @Test
    void rejectsUnapprovedVersionAndMismatchedReleaseContent() {
        var snapshot = snapshot(CASES, 1);
        assertThrows(BizException.class, () -> service(snapshot,
            release(snapshot, EvalDatasetReviewStatus.DRAFT, snapshot.contentHash()))
            .requireApprovedQualityCase("release", "target"));
        assertThrows(BizException.class, () -> service(snapshot,
            release(snapshot, EvalDatasetReviewStatus.APPROVED, "wrong"))
            .requireApprovedQualityCase("release", "target"));
        var corrupted = new EvalDatasetSnapshot(snapshot.versionId(), snapshot.evalType(), snapshot.contentHash(),
            1, CASES.replace("问题", "别的问题"), 0);
        assertThrows(BizException.class, () -> service(corrupted,
            release(snapshot, EvalDatasetReviewStatus.APPROVED, snapshot.contentHash()))
            .requireApprovedQualityCase("release", "target"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "[{\"id\":\"target\"}]",
        "[{\"id\":\"target\",\"input\":\"问题\",\"expected\":\" \"}]",
        "[{\"id\":\"target\",\"input\":7,\"expected\":\"要点\"}]"})
    void rejectsEmptyMalformedOrUnjudgeableCases(String json) {
        var snapshot = snapshot(json, 1);
        assertThrows(BizException.class, () -> service(snapshot,
            release(snapshot, EvalDatasetReviewStatus.APPROVED, snapshot.contentHash()))
            .requireApprovedQualityCase("release", "target"));
    }

    private EvalDatasetAdminService service(EvalDatasetSnapshot snapshot, EvalDatasetRelease release) {
        var snapshots = mock(EvalDatasetSnapshotStore.class);
        var releases = mock(EvalDatasetReleaseStore.class);
        var provider = mock(EvalGatewayProvider.class);
        when(snapshots.find("snapshot")).thenReturn(Optional.of(snapshot));
        when(releases.find("release")).thenReturn(Optional.of(release));
        when(provider.dataset()).thenReturn(new EvalGateway(null, null, snapshots, releases));
        return new EvalDatasetAdminService(provider, new ObjectMapper());
    }

    private EvalDatasetSnapshot snapshot(String json, int count) {
        return new EvalDatasetSnapshot("snapshot", EvalType.QUALITY,
            EvalFingerprint.of("eval-dataset-v1", EvalType.QUALITY, json), count, json, 0);
    }

    private EvalDatasetRelease release(EvalDatasetSnapshot snapshot, EvalDatasetReviewStatus status, String hash) {
        return new EvalDatasetRelease("release", EvalType.QUALITY, "审核版本", "snapshot", hash,
            snapshot.caseCount(), status, null, 41L, 42L, 100, 200L);
    }
}
