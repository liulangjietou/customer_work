package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.capability.eval.entity.EvalDatasetSnapshotDO;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalDatasetSnapshotMapper;

import java.util.Optional;

/** MyBatis 数据集快照存储；内容唯一键负责多副本并发幂等。 */
public class MybatisEvalDatasetSnapshotStore implements EvalDatasetSnapshotStore {

    private final EvalDatasetSnapshotMapper mapper;

    public MybatisEvalDatasetSnapshotStore(EvalDatasetSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public EvalDatasetSnapshot saveIfAbsent(EvalDatasetSnapshot snapshot) {
        EvalDatasetSnapshotDO row = toDO(snapshot);
        mapper.insertIgnore(row);
        EvalDatasetSnapshotDO stored = mapper.selectByContent(
            snapshot.evalType().name(), snapshot.contentHash());
        if (stored == null) {
            throw new IllegalStateException(
                "eval dataset snapshot was not persisted: " + snapshot.contentHash());
        }
        return toDomain(stored);
    }

    @Override
    public Optional<EvalDatasetSnapshot> find(String versionId) {
        EvalDatasetSnapshotDO row = mapper.selectByVersion(versionId);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    private EvalDatasetSnapshotDO toDO(EvalDatasetSnapshot snapshot) {
        EvalDatasetSnapshotDO row = new EvalDatasetSnapshotDO();
        row.setVersionId(snapshot.versionId());
        row.setEvalType(snapshot.evalType().name());
        row.setContentHash(snapshot.contentHash());
        row.setCaseCount(snapshot.caseCount());
        row.setCasesJson(snapshot.casesJson());
        row.setCreatedAtMs(snapshot.createdAtMs());
        return row;
    }

    private EvalDatasetSnapshot toDomain(EvalDatasetSnapshotDO row) {
        return new EvalDatasetSnapshot(row.getVersionId(), EvalType.valueOf(row.getEvalType()),
            row.getContentHash(), row.getCaseCount() == null ? 0 : row.getCaseCount(),
            row.getCasesJson(), row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
    }
}
