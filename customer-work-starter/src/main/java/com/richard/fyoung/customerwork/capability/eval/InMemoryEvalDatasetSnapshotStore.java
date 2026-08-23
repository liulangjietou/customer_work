package com.richard.fyoung.customerwork.capability.eval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 进程内不可变数据集快照存储。 */
public class InMemoryEvalDatasetSnapshotStore implements EvalDatasetSnapshotStore {

    private final Map<String, EvalDatasetSnapshot> byVersion = new LinkedHashMap<>();
    private final Map<String, String> versionByContent = new LinkedHashMap<>();

    @Override
    public synchronized EvalDatasetSnapshot saveIfAbsent(EvalDatasetSnapshot snapshot) {
        String contentKey = snapshot.evalType().name() + ':' + snapshot.contentHash();
        String existingVersion = versionByContent.get(contentKey);
        if (existingVersion != null) {
            return byVersion.get(existingVersion);
        }
        byVersion.put(snapshot.versionId(), snapshot);
        versionByContent.put(contentKey, snapshot.versionId());
        return snapshot;
    }

    @Override
    public synchronized Optional<EvalDatasetSnapshot> find(String versionId) {
        return Optional.ofNullable(byVersion.get(versionId));
    }
}
