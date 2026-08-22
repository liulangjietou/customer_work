package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher.PreparedRuntimeConfig;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGateOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGatePolicyRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGatePolicyVO;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.RuntimePublishGateVO;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.entity.EvalGateOverrideEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.entity.EvalGatePolicyEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.mapper.EvalGateOverrideMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.mapper.EvalGatePolicyMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalRunStore;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 评测发布门禁编排：读取策略与客服端评测事实，在既有可靠发布任务上记录通过/阻断/豁免。
 */
@Service
public class EvalReleaseGateService {

    private static final int RUN_SEARCH_LIMIT = 100;
    private static final String EXPERIMENT_BASELINE_NOTICE =
        "在线实验激活仅校验去除实验身份后的基线候选；双臂由模型认证与在线护栏治理";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final EvalGatePolicyMapper policyMapper;
    private final EvalGateOverrideMapper overrideMapper;
    private final RuntimePublishTaskMapper taskMapper;
    private final RuntimePublishTaskService taskService;
    private final EvalGatewayProvider gatewayProvider;
    private final EvalGateEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public EvalReleaseGateService(EvalGatePolicyMapper policyMapper,
                                  EvalGateOverrideMapper overrideMapper,
                                  RuntimePublishTaskMapper taskMapper,
                                  RuntimePublishTaskService taskService,
                                  EvalGatewayProvider gatewayProvider) {
        this(policyMapper, overrideMapper, taskMapper, taskService, gatewayProvider,
            new EvalGateEvaluator());
    }

    EvalReleaseGateService(EvalGatePolicyMapper policyMapper,
                           EvalGateOverrideMapper overrideMapper,
                           RuntimePublishTaskMapper taskMapper,
                           RuntimePublishTaskService taskService,
                           EvalGatewayProvider gatewayProvider,
                           EvalGateEvaluator evaluator) {
        this.policyMapper = policyMapper;
        this.overrideMapper = overrideMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.gatewayProvider = gatewayProvider;
        this.evaluator = evaluator;
    }

    /** Worker 在候选已固化、Nacos 投递前调用。 */
    public EvalGateDecision evaluateAndRecord(RuntimePublishTask task, PreparedRuntimeConfig prepared) {
        if (EvalGateStatus.OVERRIDDEN.name().equals(task.getGateStatus())) {
            return new EvalGateDecision(EvalGateStatus.OVERRIDDEN, List.of(), System.currentTimeMillis());
        }
        ModelExperimentPublishAction experimentAction = experimentAction(task);
        if (experimentAction == ModelExperimentPublishAction.DEACTIVATE) {
            EvalGateDecision decision = new EvalGateDecision(
                EvalGateStatus.NOT_REQUIRED, List.of(), System.currentTimeMillis());
            taskService.recordGateDecision(task, writeJson(prepared.versionBinding()), "[]",
                writeJson(decision), decision.status(), null);
            return decision;
        }
        List<EvalGatePolicyEntity> policies = enabledPolicies(task.getTenantId());
        if (CollectionUtils.isEmpty(policies)) {
            EvalGateDecision decision = new EvalGateDecision(
                EvalGateStatus.NOT_REQUIRED, List.of(), System.currentTimeMillis());
            taskService.recordGateDecision(task, writeJson(prepared.versionBinding()), "[]",
                writeJson(decision), decision.status(), null);
            return decision;
        }

        EvalRunStore store = gatewayProvider.get();
        EvalVersionBinding gateCandidate = experimentAction == ModelExperimentPublishAction.ACTIVATE
            ? experimentBaselineCandidate(prepared) : prepared.versionBinding();
        List<EvalGateCheckResult> checks = new ArrayList<>(policies.size());
        List<String> runIds = new ArrayList<>(policies.size());
        for (EvalGatePolicyEntity policy : policies) {
            EvalGateRule rule = toRule(policy);
            EvalRun current = selectRun(store, rule, gateCandidate);
            EvalRun baseline = current == null ? null
                : store.findBaseline(current.evalType(), current.runId()).orElse(null);
            EvalGateCheckResult check = evaluator.evaluate(
                rule, current, baseline, gateCandidate);
            if (experimentAction == ModelExperimentPublishAction.ACTIVATE) {
                check = withNotice(check, EXPERIMENT_BASELINE_NOTICE);
            }
            checks.add(check);
            if (current != null) {
                runIds.add(current.runId());
            }
        }

        boolean passed = checks.stream().allMatch(EvalGateCheckResult::passed);
        EvalGateDecision decision = new EvalGateDecision(
            passed ? EvalGateStatus.PASSED : EvalGateStatus.BLOCKED,
            checks, System.currentTimeMillis());
        String failureSummary = passed ? null : decision.summary();
        taskService.recordGateDecision(task, writeJson(prepared.versionBinding()),
            writeJson(runIds), writeJson(decision), decision.status(), failureSummary);
        return decision;
    }

    private ModelExperimentPublishAction experimentAction(RuntimePublishTask task) {
        if (!StringUtils.hasText(task.getExperimentPublishAction())) {
            return null;
        }
        try {
            return ModelExperimentPublishAction.valueOf(task.getExperimentPublishAction());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "unsupported experiment publish action: " + task.getExperimentPublishAction(), e);
        }
    }

    /**
     * 实验身份在首次激活前不可能存在对应离线评测；门禁因此校验同一载荷移除实验后的基线候选。
     * 完整实验候选仍写入任务事实，避免审计时误认为双臂本身已完成离线评测。
     */
    private EvalVersionBinding experimentBaselineCandidate(PreparedRuntimeConfig prepared) {
        try {
            CustomerWorkRuntimeConfig runtime = objectMapper.readValue(
                prepared.json(), CustomerWorkRuntimeConfig.class);
            runtime.setOnlineExperiment(null);
            return EvalVersionBinding.fromRuntimeConfig(runtime);
        } catch (Exception e) {
            throw new IllegalStateException("invalid experiment runtime candidate JSON", e);
        }
    }

    private EvalGateCheckResult withNotice(EvalGateCheckResult check, String notice) {
        List<String> notices = new ArrayList<>(check.notices());
        notices.add(notice);
        return new EvalGateCheckResult(check.evalType(), check.runId(), check.baselineRunId(),
            check.passed(), check.failures(), notices);
    }

    public List<EvalGatePolicyVO> listPolicies() {
        String tenantId = TenantContext.require();
        return policyMapper.selectList(new QueryWrapper<EvalGatePolicyEntity>()
                .eq("tenant_id", tenantId).orderByAsc("eval_type"))
            .stream().map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public EvalGatePolicyVO savePolicy(EvalType type, EvalGatePolicyRequest request, Long operatorId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(request, "request");
        String tenantId = TenantContext.require();
        LocalDateTime now = LocalDateTime.now();
        EvalGatePolicyEntity policy = new EvalGatePolicyEntity();
        policy.setTenantId(tenantId);
        policy.setEvalType(type.name());
        policy.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        policy.setMinPrimaryMetric(request.minPrimaryMetric());
        policy.setMinSecondaryMetric(request.minSecondaryMetric());
        policy.setMaxPrimaryRegression(request.maxPrimaryRegression());
        policy.setMaxSecondaryRegression(request.maxSecondaryRegression());
        policy.setCriticalCaseIdsJson(writeJson(
            request.criticalCaseIds() == null ? List.of() : request.criticalCaseIds()));
        policy.setJudgeErrorPolicy((request.judgeErrorPolicy() == null
            ? JudgeErrorPolicy.BLOCK : request.judgeErrorPolicy()).name());
        policy.setRequireArtifactMatch(Boolean.FALSE.equals(request.requireArtifactMatch()) ? 0 : 1);
        policy.setCreateBy(operatorId);
        policy.setCreateTime(now);
        policy.setUpdateBy(operatorId);
        policy.setUpdateTime(now);
        policyMapper.upsert(policy);
        return toVO(requirePolicy(tenantId, type));
    }

    public RuntimePublishGateVO taskGate(String taskId) {
        return toVO(requireTask(taskId, TenantContext.require()));
    }

    /** 重新评估不会绕过门禁，只把 BLOCKED 任务放回原状态机。 */
    @Transactional(rollbackFor = Exception.class)
    public void retry(String taskId) {
        String tenantId = TenantContext.require();
        requireTask(taskId, tenantId);
        taskService.retryGateBlocked(taskId, tenantId);
    }

    /** 紧急豁免与具体任务、候选内容哈希绑定，并追加独立审计事实。 */
    @Transactional(rollbackFor = Exception.class)
    public void override(String taskId, EvalGateOverrideRequest request, Long operatorId) {
        String tenantId = TenantContext.require();
        RuntimePublishTask task = requireTask(taskId, tenantId);
        if (!RuntimePublishStatus.BLOCKED.name().equals(task.getStatus())
            || !EvalGateStatus.BLOCKED.name().equals(task.getGateStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "只有门禁阻断的发布任务可以豁免");
        }
        if (!StringUtils.hasText(task.getContentHash())) {
            throw new BizException(ResultCode.PARAM_INVALID, "发布候选尚未固化，不能豁免");
        }

        EvalGateOverrideEntity audit = new EvalGateOverrideEntity();
        audit.setTenantId(tenantId);
        audit.setTaskId(taskId);
        audit.setCandidateContentHash(task.getContentHash());
        audit.setOperatorId(operatorId);
        audit.setReason(request.reason().trim());
        audit.setPreviousDecisionJson(task.getGateDecisionJson());
        audit.setCreatedAt(LocalDateTime.now());
        overrideMapper.insert(audit);
        taskService.overrideGateBlocked(taskId, tenantId, audit.getId());
    }

    private List<EvalGatePolicyEntity> enabledPolicies(String tenantId) {
        return policyMapper.selectList(new QueryWrapper<EvalGatePolicyEntity>()
            .eq("tenant_id", tenantId).eq("enabled", 1).orderByAsc("eval_type"));
    }

    private EvalRun selectRun(EvalRunStore store, EvalGateRule rule, EvalVersionBinding candidate) {
        List<EvalRun> recent = store.findRecent(rule.evalType(), RUN_SEARCH_LIMIT);
        if (CollectionUtils.isEmpty(recent)) {
            return null;
        }
        if (!rule.requireArtifactMatch()) {
            return recent.get(0);
        }
        return recent.stream()
            .filter(run -> run.versionBinding() != null && run.versionBinding().isComplete())
            .filter(run -> run.versionBinding().matchesCandidate(candidate))
            .findFirst()
            .orElse(recent.get(0));
    }

    private EvalGatePolicyEntity requirePolicy(String tenantId, EvalType type) {
        EvalGatePolicyEntity policy = policyMapper.selectOne(new QueryWrapper<EvalGatePolicyEntity>()
            .eq("tenant_id", tenantId).eq("eval_type", type.name()).last("LIMIT 1"));
        if (policy == null) {
            throw new IllegalStateException("eval gate policy was not persisted: " + type);
        }
        return policy;
    }

    private RuntimePublishTask requireTask(String taskId, String tenantId) {
        RuntimePublishTask task = taskMapper.selectOne(new QueryWrapper<RuntimePublishTask>()
            .eq("id", taskId).eq("tenant_id", tenantId).last("LIMIT 1"));
        if (task == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "发布任务不存在：" + taskId);
        }
        return task;
    }

    private EvalGateRule toRule(EvalGatePolicyEntity policy) {
        return new EvalGateRule(EvalType.valueOf(policy.getEvalType()),
            policy.getMinPrimaryMetric(), policy.getMinSecondaryMetric(),
            policy.getMaxPrimaryRegression(), policy.getMaxSecondaryRegression(),
            readStringList(policy.getCriticalCaseIdsJson()),
            JudgeErrorPolicy.valueOf(policy.getJudgeErrorPolicy()),
            !Integer.valueOf(0).equals(policy.getRequireArtifactMatch()));
    }

    private EvalGatePolicyVO toVO(EvalGatePolicyEntity policy) {
        EvalGateRule rule = toRule(policy);
        return new EvalGatePolicyVO(rule.evalType(), !Integer.valueOf(0).equals(policy.getEnabled()),
            rule.minPrimaryMetric(), rule.minSecondaryMetric(),
            rule.maxPrimaryRegression(), rule.maxSecondaryRegression(),
            rule.criticalCaseIds(), rule.judgeErrorPolicy(), rule.requireArtifactMatch(),
            policy.getUpdateTime());
    }

    private RuntimePublishGateVO toVO(RuntimePublishTask task) {
        return new RuntimePublishGateVO(task.getId(), task.getStatus(),
            parseStatus(task.getGateStatus()), task.getContentHash(),
            readJson(task.getCandidateVersionsJson(), EvalVersionBinding.class),
            readStringList(task.getGateEvalRunIdsJson()),
            readJson(task.getGateDecisionJson(), EvalGateDecision.class),
            task.getGateEvaluatedAtMs(), task.getGateOverrideId());
    }

    private EvalGateStatus parseStatus(String status) {
        return StringUtils.hasText(status) ? EvalGateStatus.valueOf(status) : EvalGateStatus.NOT_REQUIRED;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize eval release gate fact", e);
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("invalid eval gate string list JSON", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("invalid eval gate JSON: " + type.getSimpleName(), e);
        }
    }
}
