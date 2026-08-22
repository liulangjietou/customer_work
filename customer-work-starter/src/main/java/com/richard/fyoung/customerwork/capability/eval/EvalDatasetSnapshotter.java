package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** 把本次实际执行的用例序列规范化并在执行前固化为不可变版本。 */
public class EvalDatasetSnapshotter {

    private final EvalDatasetSnapshotStore store;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public EvalDatasetSnapshotter(EvalDatasetSnapshotStore store) {
        this.store = store;
    }

    public EvalDatasetSnapshot snapshot(EvalType type, List<?> cases) {
        try {
            String casesJson = objectMapper.writeValueAsString(List.copyOf(cases));
            return store.saveIfAbsent(EvalDatasetSnapshot.create(type, cases.size(), casesJson));
        } catch (Exception e) {
            throw new IllegalStateException("failed to snapshot eval dataset: " + type, e);
        }
    }
}
