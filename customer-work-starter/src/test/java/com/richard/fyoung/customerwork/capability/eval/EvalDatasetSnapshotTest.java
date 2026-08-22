package com.richard.fyoung.customerwork.capability.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalDatasetSnapshotTest {

    @Test
    void sameContentShouldReuseImmutableVersionWhileChangedContentCreatesNewVersion() {
        InMemoryEvalDatasetSnapshotStore store = new InMemoryEvalDatasetSnapshotStore();
        EvalDatasetSnapshotter snapshotter = new EvalDatasetSnapshotter(store);
        List<EvalCase> firstCases = List.of(
            new EvalCase("critical-refund", "我要退款", "after_sales", "refund"));

        EvalDatasetSnapshot first = snapshotter.snapshot(EvalType.INTENT, firstCases);
        EvalDatasetSnapshot repeated = snapshotter.snapshot(EvalType.INTENT, firstCases);
        EvalDatasetSnapshot changed = snapshotter.snapshot(EvalType.INTENT, List.of(
            new EvalCase("critical-refund", "我要立刻退款", "after_sales", "refund")));

        assertEquals(first.versionId(), repeated.versionId());
        assertEquals(first.contentHash(), repeated.contentHash());
        assertNotEquals(first.versionId(), changed.versionId());
        assertTrue(first.casesJson().contains("critical-refund"));
        assertEquals(first, store.find(first.versionId()).orElseThrow());
    }
}
