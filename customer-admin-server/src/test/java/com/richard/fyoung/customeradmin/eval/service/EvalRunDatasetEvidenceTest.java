package com.richard.fyoung.customeradmin.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.eval.config.EvalGateway;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshotStore;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用实际内容指纹验证运行与不可变快照的绑定，不用失败列表反推目标一定参评。 */
class EvalRunDatasetEvidenceTest {

    private static final String CASES = "[{\"id\":\"target\"},{\"id\":\"another\"}]";

    @Test
    void acceptsActualTargetWithoutReadingTheMutableWorkingSet() {
        EvalDatasetSnapshot snapshot = snapshot(EvalType.INTENT, 2, CASES);
        var service = service(Optional.of(snapshot));
        // caseStore 刻意为空：完成后的判定只能使用运行时快照，停用或编辑当前用例不改写历史事实。
        assertDoesNotThrow(() -> service.requireExecutedCase(
            run(snapshot, EvalType.INTENT, 2, 2, 1, List.of("another")), EvalType.INTENT, "target"));
        BizException omitted = assertThrows(BizException.class, () -> service.requireExecutedCase(
            run(snapshot, EvalType.INTENT, 2, 2, 1, List.of("another")), EvalType.INTENT, "omitted"));
        assertTrue(omitted.getMessage().contains("目标回归用例未进入本次评测"));
    }

    @Test
    void rejectsMissingSnapshotAndDifferentEvaluationTypes() {
        EvalDatasetSnapshot intent = snapshot(EvalType.INTENT, 2, CASES);
        EvalRun run = run(intent, EvalType.INTENT, 2, 2, 2, List.of());
        assertThrows(BizException.class, () -> service(Optional.empty())
            .requireExecutedCase(run, EvalType.INTENT, "target"));
        assertThrows(BizException.class, () -> service(Optional.of(intent))
            .requireExecutedCase(run, EvalType.QUALITY, "target"));
        EvalDatasetSnapshot quality = snapshot(EvalType.QUALITY, 2, CASES);
        assertThrows(BizException.class, () -> service(Optional.of(quality))
            .requireExecutedCase(run(quality, EvalType.INTENT, 2, 2, 2, List.of()),
                EvalType.INTENT, "target"));
    }

    @Test
    void rejectsBothChangedContentAndWrongRunFingerprint() {
        EvalDatasetSnapshot original = snapshot(EvalType.INTENT, 2, CASES);
        EvalDatasetSnapshot changed = new EvalDatasetSnapshot(original.versionId(), original.evalType(),
            original.contentHash(), 2, CASES.replace("another", "changed"), 0L);
        assertThrows(BizException.class, () -> service(Optional.of(changed)).requireExecutedCase(
            run(original, EvalType.INTENT, 2, 2, 2, List.of()), EvalType.INTENT, "target"));
        EvalDatasetSnapshot different = snapshot(EvalType.INTENT, 2, CASES.replace("another", "changed"));
        assertThrows(BizException.class, () -> service(Optional.of(original)).requireExecutedCase(
            run(different, EvalType.INTENT, 2, 2, 2, List.of()), EvalType.INTENT, "target"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "[{\"id\":\"target\"},{\"id\":\"target\"}]",
        "[{\"id\":\"target\"},{}]", "[]"})
    void rejectsMalformedDuplicateOrIncompleteSnapshot(String casesJson) {
        EvalDatasetSnapshot snapshot = snapshot(EvalType.INTENT, 2, casesJson);
        assertThrows(BizException.class, () -> service(Optional.of(snapshot)).requireExecutedCase(
            run(snapshot, EvalType.INTENT, 2, 2, 2, List.of()), EvalType.INTENT, "target"));
    }

    @Test
    void requiresExactRunCountsAndKnownUniqueFailureIds() {
        EvalDatasetSnapshot snapshot = snapshot(EvalType.INTENT, 2, CASES);
        var service = service(Optional.of(snapshot));
        List<EvalRun> inconsistent = List.of(
            run(snapshot, EvalType.INTENT, 1, 2, 1, List.of()),
            run(snapshot, EvalType.INTENT, 2, 1, 2, List.of()),
            run(snapshot, EvalType.INTENT, 2, 2, 1, List.of()),
            run(snapshot, EvalType.INTENT, 2, 2, 1, List.of("unknown")),
            run(snapshot, EvalType.INTENT, 2, 2, 0, List.of("another", "another")));
        for (EvalRun run : inconsistent) {
            assertThrows(BizException.class, () -> service.requireExecutedCase(run, EvalType.INTENT, "target"));
        }
        EvalDatasetSnapshot wrongCount = snapshot(EvalType.INTENT, 1, CASES);
        assertThrows(BizException.class, () -> service(Optional.of(wrongCount)).requireExecutedCase(
            run(wrongCount, EvalType.INTENT, 2, 2, 2, List.of()), EvalType.INTENT, "target"));
    }

    @Test
    void preservesStorageFailureInsteadOfTurningItIntoAnEmptyDataset() {
        var provider = mock(EvalGatewayProvider.class);
        var unavailable = new IllegalStateException("eval datasource unavailable");
        when(provider.dataset()).thenThrow(unavailable);
        var service = new EvalDatasetAdminService(provider, new ObjectMapper());
        var snapshot = snapshot(EvalType.INTENT, 2, CASES);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> service.requireExecutedCase(
            run(snapshot, EvalType.INTENT, 2, 2, 2, List.of()), EvalType.INTENT, "target")));
    }

    private EvalDatasetAdminService service(Optional<EvalDatasetSnapshot> snapshot) {
        var store = mock(EvalDatasetSnapshotStore.class);
        when(store.find("dataset-1")).thenReturn(snapshot);
        var provider = mock(EvalGatewayProvider.class);
        when(provider.dataset()).thenReturn(new EvalGateway(null, null, store, null));
        return new EvalDatasetAdminService(provider, new ObjectMapper());
    }

    private EvalDatasetSnapshot snapshot(EvalType type, int count, String json) {
        return new EvalDatasetSnapshot("dataset-1", type,
            EvalFingerprint.of("eval-dataset-v1", type, json), count, json, 0L);
    }

    private EvalRun run(EvalDatasetSnapshot snapshot, EvalType type, int total, int datasetSize,
                        int passed, List<String> failedIds) {
        var binding = new EvalVersionBinding(snapshot.versionId(), snapshot.contentHash(),
            "model", "prompt", "agent", "knowledge", "tool", "judge", "rubric");
        return new EvalRun("run-1", type, total, passed, 1.0d, 1.0d, failedIds, List.of(), Map.of(),
            EvalTrigger.MANUAL, datasetSize, binding, null, 0L);
    }
}
