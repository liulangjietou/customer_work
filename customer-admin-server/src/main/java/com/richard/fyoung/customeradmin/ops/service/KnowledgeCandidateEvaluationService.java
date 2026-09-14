package com.richard.fyoung.customeradmin.ops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateEvaluation;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateReviewVO;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateEvaluationStore;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalExecutionDeadline;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.KnowledgeCandidateTrialRunner;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalCase;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalRunner;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 使用真正的只读 ReAct 工具链对照试用，结果与原有客服端全局评测基线隔离。 */
@Service
public class KnowledgeCandidateEvaluationService {
    private static final double MIN_PRIMARY_METRIC = 3.0d / 5.0d;
    private static final Duration JUDGE_TIMEOUT = Duration.ofSeconds(120);
    private final KnowledgeCandidateBindingService bindings;
    private final KnowledgeTrialModelService models;
    private final OpsGatewayProvider gateway;
    private final EvalDatasetAdminService datasets;
    private final KnowledgeCandidateEvaluationStore store;
    private final ObjectMapper mapper;
    private final KnowledgeCandidateTrialRunner runner;

    public KnowledgeCandidateEvaluationService(KnowledgeCandidateBindingService bindings,
        KnowledgeTrialModelService models, OpsGatewayProvider gateway, EvalDatasetAdminService datasets,
        KnowledgeCandidateEvaluationStore store, ObjectMapper mapper, KnowledgeCandidateTrialRunner runner) {
        this.bindings = bindings; this.models = models; this.gateway = gateway;
        this.datasets = datasets; this.store = store; this.mapper = mapper; this.runner = runner;
    }

    /** 外部模型调用不持有 Admin 事务；完成后的事实由父改进记录在其短事务中一并落库。 */
    public KnowledgeCandidateEvaluation run(KnowledgeCandidateBinding binding, String sourceKey, String remark, long deadlineAtMs) {
        var deadline = new EvalExecutionDeadline(deadlineAtMs);
        deadline.limit(JUDGE_TIMEOUT);
        bindings.requireCurrent(binding, sourceKey);
        var cases = cases(binding);
        var mainModel = models.build(binding.model());
        var judgeModel = models.build(binding.judge());
        var knowledgeMapper = gateway.get().knowledgeMapper();
        var knowledge = binding.knowledge();
        var baselineIdentity = new KnowledgeCandidateTrialRunner.TrialIdentity(binding.agentCode(), binding.baselineVersions());
        var candidateIdentity = new KnowledgeCandidateTrialRunner.TrialIdentity(binding.agentCode(), binding.versions());
        var baselineTrial = runner.run(mainModel, binding.systemPrompt(), binding.maxIters(), cases,
            knowledgeMapper, knowledge.baselineCorpusJson(), knowledge.candidateRowId(), baselineIdentity, deadline);
        var candidateTrial = runner.run(mainModel, binding.systemPrompt(), binding.maxIters(), cases,
            knowledgeMapper, knowledge.corpusJson(), knowledge.candidateRowId(), candidateIdentity, deadline);
        var baselineJudge = new QualityEvalRunner(message -> runner.judge(judgeModel, message, baselineIdentity, deadline));
        var candidateJudge = new QualityEvalRunner(message -> runner.judge(judgeModel, message, candidateIdentity, deadline));
        EvalRun baseline = EvalRun.fromQuality(baselineJudge.run(cases, baselineTrial.replies()), EvalTrigger.MANUAL,
            binding.baselineVersions(), remark);
        EvalRun current = EvalRun.fromQuality(candidateJudge.run(cases, candidateTrial.replies()), EvalTrigger.MANUAL,
            binding.versions(), remark);
        deadline.limit(JUDGE_TIMEOUT);
        return new KnowledgeCandidateEvaluation(knowledge.tenantId(), binding.improvementId(), binding.fingerprint(),
            baseline, current, baselineTrial.replies(), candidateTrial.replies(), candidateTrial.candidateRecalledCaseIds());
    }

    /** 发布前和复评完成时共用同一证据核验，目标高分、实际召回和存量回归分别检查。 */
    public List<String> failures(KnowledgeCandidateBinding binding, KnowledgeCandidateEvaluation evaluation) {
        evaluation.requireMatches(binding);
        datasets.requireExecutedCase(evaluation.baseline(), EvalType.QUALITY, binding.targetCaseId());
        datasets.requireExecutedCase(evaluation.current(), EvalType.QUALITY, binding.targetCaseId());
        var failures = new ArrayList<String>();
        if (!evaluation.baseline().gatePassed() || !evaluation.current().gatePassed()) {
            failures.add("对照组或候选组 Judge 运行不完整");
        }
        if (!evaluation.candidateRecalledCaseIds().contains(binding.targetCaseId())) {
            failures.add("目标问题未实际检索到本次知识候选");
        }
        var ids = new HashSet<>(cases(binding).stream().map(QualityEvalCase::id).toList());
        if (!ids.containsAll(evaluation.candidateRecalledCaseIds())
            || new HashSet<>(evaluation.candidateRecalledCaseIds()).size() != evaluation.candidateRecalledCaseIds().size()) {
            failures.add("候选召回事实与冻结用例不一致");
        }
        if (evaluation.current().failedCaseIds().contains(binding.targetCaseId())) failures.add("目标回归用例仍失败");
        EvalComparison comparison = evaluation.comparison();
        if (!comparison.regressions().isEmpty()) failures.add("出现新增回归：" + comparison.regressions());
        if (comparison.verdict() == EvalComparison.Verdict.REGRESSED) failures.add("候选组整体质量较对照组下降");
        if (!Double.isFinite(evaluation.current().primaryMetric())
            || evaluation.current().primaryMetric() < MIN_PRIMARY_METRIC) failures.add("候选组平均质量未达到通过线");
        return List.copyOf(failures);
    }

    /** 与父记录使用同一 Admin 事务，存储失败不得留下 READY_TO_PUBLISH。 */
    public void save(KnowledgeCandidateEvaluation evaluation) {
        if (!TenantContext.require().equals(evaluation.tenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识评测不存在");
        }
        store.save(evaluation);
    }

    /** 只回读当前绑定的实际运行，没有结果时不能借用全局同类型评测。 */
    public KnowledgeCandidateEvaluation require(KnowledgeCandidateBinding binding, String runId) {
        return store.find(TenantContext.require(), binding.improvementId(), binding.fingerprint(), runId)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识评测不存在"));
    }

    /** 回读历史事实不要求当前配置仍有效；执行和发布另行核对，历史失败记录始终可供排查。 */
    public KnowledgeCandidateReviewVO review(KnowledgeCandidateBinding binding, String runId) {
        KnowledgeCandidateEvaluation evaluation = StringUtils.hasText(runId) ? require(binding, runId) : null;
        if (evaluation != null) evaluation.requireMatches(binding);
        var cases = cases(binding);
        var rows = new ArrayList<KnowledgeCandidateReviewVO.CaseReview>();
        for (int i = 0; i < cases.size(); i++) {
            var item = cases.get(i);
            rows.add(new KnowledgeCandidateReviewVO.CaseReview(item.id(), item.input(), item.expected(),
                evaluation == null ? null : evaluation.baselineReplies().get(i),
                evaluation == null ? null : evaluation.candidateReplies().get(i),
                evaluation != null && evaluation.candidateRecalledCaseIds().contains(item.id()),
                evaluation != null && evaluation.baseline().failedCaseIds().contains(item.id()),
                evaluation != null && evaluation.current().failedCaseIds().contains(item.id())));
        }
        return new KnowledgeCandidateReviewVO(binding.knowledge().candidateId(), binding.knowledge().candidateRevision(),
            binding.knowledge().sourceReviewRevision(), binding.agentId(), binding.agentCode(), binding.model().deploymentId(),
            binding.model().model(), binding.judge().deploymentId(), binding.judge().model(), binding.datasetReleaseId(),
            binding.dataset().versionId(), binding.targetCaseId(), binding.fingerprint(),
            evaluation == null ? null : evaluation.comparison(), List.copyOf(rows));
    }

    private List<QualityEvalCase> cases(KnowledgeCandidateBinding binding) {
        try { return Arrays.asList(mapper.readValue(binding.dataset().casesJson(), QualityEvalCase[].class)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("frozen knowledge cases are unreadable", e); }
    }

}
