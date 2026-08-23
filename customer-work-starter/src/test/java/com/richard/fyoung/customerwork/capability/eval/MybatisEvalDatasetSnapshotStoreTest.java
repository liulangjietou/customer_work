package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.capability.eval.entity.EvalDatasetSnapshotDO;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalDatasetSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisEvalDatasetSnapshotStoreTest {

    @Test
    void concurrentDuplicateShouldReturnDatabaseWinnerWithoutUpdatingIt() {
        EvalDatasetSnapshotMapper mapper = mock(EvalDatasetSnapshotMapper.class);
        MybatisEvalDatasetSnapshotStore store = new MybatisEvalDatasetSnapshotStore(mapper);
        EvalDatasetSnapshot candidate = new EvalDatasetSnapshot(
            "candidate", EvalType.INTENT, "hash-1", 2, "[]", 10L);
        EvalDatasetSnapshotDO winner = new EvalDatasetSnapshotDO();
        winner.setVersionId("winner");
        winner.setEvalType(EvalType.INTENT.name());
        winner.setContentHash("hash-1");
        winner.setCaseCount(2);
        winner.setCasesJson("[]");
        winner.setCreatedAtMs(1L);
        when(mapper.selectByContent(EvalType.INTENT.name(), "hash-1")).thenReturn(winner);

        EvalDatasetSnapshot stored = store.saveIfAbsent(candidate);

        assertEquals("winner", stored.versionId());
        ArgumentCaptor<EvalDatasetSnapshotDO> captor = ArgumentCaptor.forClass(EvalDatasetSnapshotDO.class);
        verify(mapper).insertIgnore(captor.capture());
        assertEquals("candidate", captor.getValue().getVersionId());
        verify(mapper).selectByContent(EvalType.INTENT.name(), "hash-1");
    }
}
