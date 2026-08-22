package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.capability.eval.entity.EvalRunDO;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisEvalRunStoreVersionBindingTest {

    @Test
    void saveShouldPersistDatasetColumnsAndCompleteVersionBinding() {
        EvalRunMapper mapper = mock(EvalRunMapper.class);
        MybatisEvalRunStore store = new MybatisEvalRunStore(mapper);
        EvalVersionBinding binding = binding();
        EvalRun run = new EvalRun("run-1", EvalType.INTENT, 2, 2, 1.0, 1.0,
            List.of(), List.of(), Map.of("accuracy", 1.0), EvalTrigger.API, 2,
            binding, "release gate", 10L);

        store.save(run);

        ArgumentCaptor<EvalRunDO> captor = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(mapper).insert(captor.capture());
        EvalRunDO row = captor.getValue();
        assertEquals("dataset-v1", row.getDatasetVersionId());
        assertEquals("dataset-hash", row.getDatasetFingerprint());
        assertEquals("prompt-v1", row.getPromptFingerprint());
        assertTrue(row.getVersionBindingJson().contains("\"modelVersion\":\"model-v1\""));
    }

    @Test
    void readShouldRestoreCompleteBindingAndKeepLegacyRowsFailClosed() {
        EvalRunMapper mapper = mock(EvalRunMapper.class);
        MybatisEvalRunStore store = new MybatisEvalRunStore(mapper);
        EvalRunDO current = row("current", """
            {"datasetVersion":"dataset-v1","datasetFingerprint":"dataset-hash",
             "modelVersion":"model-v1","promptVersion":"prompt-v1","agentVersion":"agent-v1",
             "knowledgeBaseVersion":"kb-v1","toolVersion":"tool-v1",
             "judgeVersion":"NOT_APPLICABLE","rubricVersion":"rubric-v1"}
            """);
        EvalRunDO legacy = row("legacy", null);
        legacy.setPromptFingerprint("legacy-prompt");
        when(mapper.selectById("current")).thenReturn(current);
        when(mapper.selectById("legacy")).thenReturn(legacy);

        EvalRun restored = store.find("current").orElseThrow();
        EvalRun restoredLegacy = store.find("legacy").orElseThrow();

        assertTrue(restored.versionBinding().isComplete());
        assertEquals("model-v1", restored.versionBinding().modelVersion());
        assertFalse(restoredLegacy.versionBinding().isComplete());
        assertEquals("legacy-prompt", restoredLegacy.promptFingerprint());
    }

    private EvalVersionBinding binding() {
        return new EvalVersionBinding("dataset-v1", "dataset-hash", "model-v1", "prompt-v1",
            "agent-v1", "kb-v1", "tool-v1", "NOT_APPLICABLE", "rubric-v1");
    }

    private EvalRunDO row(String runId, String versionBindingJson) {
        EvalRunDO row = new EvalRunDO();
        row.setRunId(runId);
        row.setEvalType(EvalType.INTENT.name());
        row.setTotal(1);
        row.setPassed(1);
        row.setPrimaryMetric(1.0);
        row.setSecondaryMetric(1.0);
        row.setFailedCaseIdsJson("[]");
        row.setFailuresJson("[]");
        row.setMetricsJson("{}");
        row.setTriggerSource(EvalTrigger.API.name());
        row.setDatasetSize(1);
        row.setVersionBindingJson(versionBindingJson);
        row.setCreatedAtMs(1L);
        return row;
    }
}
