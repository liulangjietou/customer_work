package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.capability.eval.entity.EvalDatasetReleaseDO;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalDatasetReleaseMapper;

import java.util.List;
import java.util.Optional;

/** MyBatis 命名版本存储；审核用条件更新实现多副本 CAS。 */
public class MybatisEvalDatasetReleaseStore implements EvalDatasetReleaseStore {

    private final EvalDatasetReleaseMapper mapper;

    public MybatisEvalDatasetReleaseStore(EvalDatasetReleaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(EvalDatasetRelease release) {
        mapper.insert(toDO(release));
    }

    @Override
    public Optional<EvalDatasetRelease> find(String releaseId) {
        EvalDatasetReleaseDO row = mapper.selectByReleaseId(releaseId);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public List<EvalDatasetRelease> findByType(EvalType type) {
        return mapper.selectByType(type.name()).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean review(String releaseId, EvalDatasetReviewStatus target, String comment,
                          Long reviewerId, long reviewedAtMs) {
        return mapper.review(releaseId, target.name(), comment, reviewerId, reviewedAtMs) == 1;
    }

    private EvalDatasetReleaseDO toDO(EvalDatasetRelease release) {
        EvalDatasetReleaseDO row = new EvalDatasetReleaseDO();
        row.setReleaseId(release.releaseId());
        row.setEvalType(release.evalType().name());
        row.setVersionName(release.versionName());
        row.setSnapshotVersionId(release.snapshotVersionId());
        row.setContentHash(release.contentHash());
        row.setCaseCount(release.caseCount());
        row.setStatus(release.status().name());
        row.setReviewComment(release.reviewComment());
        row.setCreatedBy(release.createdBy());
        row.setReviewedBy(release.reviewedBy());
        row.setCreatedAtMs(release.createdAtMs());
        row.setReviewedAtMs(release.reviewedAtMs());
        return row;
    }

    private EvalDatasetRelease toDomain(EvalDatasetReleaseDO row) {
        return new EvalDatasetRelease(row.getReleaseId(), EvalType.valueOf(row.getEvalType()),
            row.getVersionName(), row.getSnapshotVersionId(), row.getContentHash(),
            row.getCaseCount() == null ? 0 : row.getCaseCount(),
            EvalDatasetReviewStatus.valueOf(row.getStatus()), row.getReviewComment(),
            row.getCreatedBy(), row.getReviewedBy(), row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs(),
            row.getReviewedAtMs());
    }
}
