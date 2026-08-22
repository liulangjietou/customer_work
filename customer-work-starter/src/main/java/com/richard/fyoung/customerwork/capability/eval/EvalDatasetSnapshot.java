package com.richard.fyoung.customerwork.capability.eval;

import java.util.UUID;

/**
 * 评测数据集的不可变内容快照。
 *
 * @param versionId   快照版本 ID
 * @param evalType    评测类型
 * @param contentHash 规范化用例 JSON 的 SHA-256
 * @param caseCount   用例数
 * @param casesJson   本次实际执行的完整用例 JSON
 * @param createdAtMs 首次观测时间
 */
public record EvalDatasetSnapshot(
    String versionId,
    EvalType evalType,
    String contentHash,
    int caseCount,
    String casesJson,
    long createdAtMs
) {

    static EvalDatasetSnapshot create(EvalType type, int caseCount, String casesJson) {
        return new EvalDatasetSnapshot(UUID.randomUUID().toString(), type,
            EvalFingerprint.of("eval-dataset-v1", type, casesJson), caseCount,
            casesJson, System.currentTimeMillis());
    }
}
