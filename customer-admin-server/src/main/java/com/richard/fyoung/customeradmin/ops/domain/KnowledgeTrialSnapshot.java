package com.richard.fyoung.customeradmin.ops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;

/** 候选正文、来源复核与正式 FAQ 的完整冻结输入，只作为内部评测证据，不由浏览器提交。 */
public record KnowledgeTrialSnapshot(String tenantId, String candidateId, long candidateRevision,
                                      long sourceReviewRevision, String candidateContentHash,
                                      String baselineFingerprint, String baselineCorpusJson,
                                      String corpusJson, long candidateRowId) {

    /** 与实际送入检索的 JSON 共同生成制品指纹，正式知识或候选的任何有效变化都会改变结果。 */
    @JsonIgnore
    public String fingerprint() {
        return EvalFingerprint.of("knowledge-trial-snapshot-v1", tenantId, candidateId, candidateRevision,
            sourceReviewRevision, candidateContentHash, baselineFingerprint, baselineCorpusJson, corpusJson, candidateRowId);
    }
}
