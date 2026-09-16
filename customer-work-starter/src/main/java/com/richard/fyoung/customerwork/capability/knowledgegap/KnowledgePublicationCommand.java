package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;

/** 后台已审核的不可变发布意图；租户取执行上下文，正文来自已评测候选，不能直接接受浏览器输入。 */
public record KnowledgePublicationCommand(String taskId, long improvementId, String candidateId,
    long candidateRevision, String artifactFingerprint, String evaluationRunId, String questionHash,
    long sourceReviewRevision, String baselineCorpusJson, String title, String content, String keyword,
    long requestedBy) {

    /** 回执绑定完整意图，同一任务换正文、操作者或证据都不能重用旧成功结果。 */
    public String fingerprint(String tenant) {
        return EvalFingerprint.of("knowledge-publication-v1", tenant, taskId, improvementId, candidateId,
            candidateRevision, artifactFingerprint, evaluationRunId, questionHash, sourceReviewRevision,
            baselineCorpusJson, title, content, keyword, requestedBy);
    }

    /** 正式来源指向确切候选修订，不把评测虚拟行号当作数据库主键。 */
    public String source() {
        return "knowledge-candidate/" + candidateId + "/v" + candidateRevision;
    }
}
