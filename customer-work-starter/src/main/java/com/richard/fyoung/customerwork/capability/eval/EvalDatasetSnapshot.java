package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;
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

    private static final String FINGERPRINT_SCOPE = "eval-dataset-v1";

    /** 核验存储内容仍对应创建快照时的指纹，避免损坏的内容被当成评测证据。 */
    @JsonIgnore
    public boolean isContentIntact() {
        return casesJson != null && Objects.equals(contentHash,
            EvalFingerprint.of(FINGERPRINT_SCOPE, evalType, casesJson));
    }

    static EvalDatasetSnapshot create(EvalType type, int caseCount, String casesJson) {
        return new EvalDatasetSnapshot(UUID.randomUUID().toString(), type,
            EvalFingerprint.of(FINGERPRINT_SCOPE, type, casesJson), caseCount,
            casesJson, System.currentTimeMillis());
    }
}
