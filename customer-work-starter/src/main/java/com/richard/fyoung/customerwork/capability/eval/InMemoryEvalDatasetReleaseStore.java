package com.richard.fyoung.customerwork.capability.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 单进程命名版本存储，供离线运行与单元测试使用。 */
public class InMemoryEvalDatasetReleaseStore implements EvalDatasetReleaseStore {

    private final Map<String, EvalDatasetRelease> releases = new LinkedHashMap<>();

    @Override
    public synchronized void create(EvalDatasetRelease release) {
        boolean duplicateName = releases.values().stream().anyMatch(item ->
            item.evalType() == release.evalType() && item.versionName().equals(release.versionName()));
        if (releases.containsKey(release.releaseId()) || duplicateName) {
            throw new EvalDatasetReleaseConflictException(
                "eval dataset release already exists: " + release.versionName());
        }
        releases.put(release.releaseId(), release);
    }

    @Override
    public synchronized Optional<EvalDatasetRelease> find(String releaseId) {
        return Optional.ofNullable(releases.get(releaseId));
    }

    @Override
    public synchronized List<EvalDatasetRelease> findByType(EvalType type) {
        List<EvalDatasetRelease> result = new ArrayList<>();
        for (EvalDatasetRelease release : releases.values()) {
            if (release.evalType() == type) {
                result.add(release);
            }
        }
        result.sort(Comparator.comparingLong(EvalDatasetRelease::createdAtMs).reversed());
        return List.copyOf(result);
    }

    @Override
    public synchronized boolean review(String releaseId, EvalDatasetReviewStatus target,
                                       String comment, Long reviewerId, long reviewedAtMs) {
        EvalDatasetRelease current = releases.get(releaseId);
        if (current == null || current.status() != EvalDatasetReviewStatus.DRAFT) {
            return false;
        }
        releases.put(releaseId, new EvalDatasetRelease(current.releaseId(), current.evalType(),
            current.versionName(), current.snapshotVersionId(), current.contentHash(), current.caseCount(),
            target, comment, current.createdBy(), reviewerId, current.createdAtMs(), reviewedAtMs));
        return true;
    }
}
