package com.richard.fyoung.customeradmin.ops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersion;

/** 改进记录指向的不可变知识候选输入；正文、模型、提示词和评测集都在执行前确定。 */
public record KnowledgeCandidateBinding(long improvementId, long agentId, String agentCode,
                                        String systemPrompt, int maxIters, KnowledgeTrialSnapshot knowledge,
                                        FrozenKnowledgeModel model, FrozenKnowledgeModel judge,
                                        String datasetReleaseId, EvalDatasetSnapshot dataset, String targetCaseId,
                                        String toolVersion, String rubricVersion) {
    public static final String ARTIFACT_TYPE = "KNOWLEDGE_CANDIDATE";
    public static final String CURRENT_TOOL_VERSION = "knowledge-readonly-react-v3";

    /** 绑定指纹不含操作人和时间，同一输入的重复提交能回读原绑定。 */
    @JsonIgnore
    public String fingerprint() {
        return EvalFingerprint.of("knowledge-candidate-binding-v1", improvementId, agentId, agentCode,
            systemPrompt, maxIters, knowledge.fingerprint(), model.fingerprint(), judge.fingerprint(),
            datasetReleaseId, dataset.versionId(), dataset.evalType(), dataset.caseCount(),
            dataset.contentHash(), dataset.casesJson(), targetCaseId,
            toolVersion, rubricVersion);
    }

    /** 与既有评测算法共用版本视图；工具维度明确表示只读知识回归。 */
    @JsonIgnore
    public EvalVersionBinding versions() {
        return new EvalVersionBinding(dataset.versionId(), dataset.contentHash(), model.fingerprint(),
            PromptVersion.fingerprintOf(systemPrompt),
            EvalFingerprint.of("knowledge-trial-agent-v1", agentId, agentCode, maxIters),
            knowledge.fingerprint(), toolVersion, judge.fingerprint(), rubricVersion);
    }

    /** 对照组只有知识语料维度不同，禁止混入其它用例、模型或历史运行作基线。 */
    @JsonIgnore
    public EvalVersionBinding baselineVersions() {
        EvalVersionBinding current = versions();
        return new EvalVersionBinding(current.datasetVersion(), current.datasetFingerprint(), current.modelVersion(),
            current.promptVersion(), current.agentVersion(), knowledge.baselineFingerprint(), current.toolVersion(),
            current.judgeVersion(), current.rubricVersion());
    }
}
