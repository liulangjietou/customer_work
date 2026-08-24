package com.richard.fyoung.customerwork.capability.eval;

import java.util.List;
import java.util.Optional;

/** 命名数据集版本存储；内容不可变，唯一允许的更新是 DRAFT 的一次性审核迁移。 */
public interface EvalDatasetReleaseStore {

    void create(EvalDatasetRelease release);

    Optional<EvalDatasetRelease> find(String releaseId);

    List<EvalDatasetRelease> findByType(EvalType type);

    boolean review(String releaseId, EvalDatasetReviewStatus target, String comment,
                   Long reviewerId, long reviewedAtMs);
}
