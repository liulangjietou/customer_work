package com.richard.fyoung.customeradmin.improvement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.badcase.config.BadcaseGatewayProvider;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.service.EvalAdminService;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementSignalGatewayProvider;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementCaseStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementEffectStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementReevaluationStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSlaStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSourceType;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementBindArtifactRequest;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementCaseVO;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementEvalCaseRequest;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementTriageRequest;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customeradmin.improvement.jdbc.ImprovementSignalGateway;
import com.richard.fyoung.customeradmin.improvement.jdbc.ImprovementSourceFact;
import com.richard.fyoung.customeradmin.improvement.mapper.AgentImprovementCaseMapper;
import com.richard.fyoung.customerwork.capability.badcase.Badcase;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseSource;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.capability.eval.PersistedEvalCase;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 智能体改进闭环：责任认领 → 精确候选 → 用例复评 → 可靠发布 → revision 线上观测。
 *
 * <p>原始信号和评测用例在客服库，状态机与发布任务在 Admin 库。跨库只读取信号或创建可独立追溯的
 * 评测用例；发布任务和状态推进始终在同一 Admin 事务中，避免“页面显示已发布但任务没落库”。</p>
 */
@Service
public class ImprovementCaseService {

    private static final String ARTIFACT_TYPE = "AGENT_RUNTIME";
    private static final long NO_ACTION_AT = Long.MAX_VALUE;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final AgentImprovementCaseMapper caseMapper;
    private final ImprovementSignalGatewayProvider signalGatewayProvider;
    private final BadcaseGatewayProvider badcaseGatewayProvider;
    private final AiAgentMapper agentMapper;
    private final CustomerWorkConfigPublisher publisher;
    private final EvalAdminService evalAdminService;
    private final RuntimePublishTaskService publishTaskService;
    private final RuntimePublishTaskMapper publishTaskMapper;
    private final ImprovementAutomationProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ImprovementCaseService(AgentImprovementCaseMapper caseMapper,
                                  ImprovementSignalGatewayProvider signalGatewayProvider,
                                  BadcaseGatewayProvider badcaseGatewayProvider,
                                  AiAgentMapper agentMapper,
                                  CustomerWorkConfigPublisher publisher,
                                  EvalAdminService evalAdminService,
                                  RuntimePublishTaskService publishTaskService,
                                  RuntimePublishTaskMapper publishTaskMapper,
                                  ImprovementAutomationProperties properties,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.caseMapper = caseMapper;
        this.signalGatewayProvider = signalGatewayProvider;
        this.badcaseGatewayProvider = badcaseGatewayProvider;
        this.agentMapper = agentMapper;
        this.publisher = publisher;
        this.evalAdminService = evalAdminService;
        this.publishTaskService = publishTaskService;
        this.publishTaskMapper = publishTaskMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<ImprovementCaseVO> findBySource(ImprovementSourceType sourceType, String sourceKey) {
        AgentImprovementCase row = caseMapper.selectOne(new LambdaQueryWrapper<AgentImprovementCase>()
            .eq(AgentImprovementCase::getSourceType, sourceType.name())
            .eq(AgentImprovementCase::getSourceKey, sourceKey)
            .last("LIMIT 1"));
        return Optional.ofNullable(row).map(this::toVO);
    }

    public ImprovementCaseVO detail(Long id) {
        return toVO(require(id));
    }

    public ImprovementCaseVO triage(ImprovementSourceType sourceType, String sourceKey,
                                    ImprovementTriageRequest request, String currentOperator) {
        long now = System.currentTimeMillis();
        String owner = StringUtils.hasText(request.ownerId())
            ? request.ownerId().trim() : currentOperator;
        if (!StringUtils.hasText(owner) || request.slaDueAtMs() <= now) {
            throw invalid("责任人不能为空，SLA 截止时间必须晚于当前时间");
        }
        ImprovementSourceFact source = requireSource(sourceType, sourceKey);
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = caseMapper.selectOne(new LambdaQueryWrapper<AgentImprovementCase>()
                .eq(AgentImprovementCase::getSourceType, sourceType.name())
                .eq(AgentImprovementCase::getSourceKey, sourceKey)
                .last("LIMIT 1 FOR UPDATE"));
            if (row == null) {
                row = new AgentImprovementCase();
                row.setTenantId(TenantContext.require());
                row.setSourceType(sourceType.name());
                row.setSourceKey(sourceKey);
                row.setSignalHash(source.getSignalHash());
                row.setSourceSignalCount(value(source.getSignalCount()));
                row.setStatus(ImprovementCaseStatus.OWNED.name());
                row.setReevaluationStatus(ImprovementReevaluationStatus.NOT_RUN.name());
                row.setEffectStatus(ImprovementEffectStatus.NOT_STARTED.name());
                row.setObservedCalls(0L);
                row.setObservedSignals(0L);
                row.setNextActionAtMs(NO_ACTION_AT);
                row.setLeaseUntilMs(0L);
                row.setAutomationFailures(0);
                row.setCreatedAtMs(now);
                row.setUpdatedAtMs(now);
                row.setEvalCaseId(source.getEvalCaseId());
                row.setOwnerId(owner);
                row.setSlaDueAtMs(request.slaDueAtMs());
                caseMapper.insert(row);
            } else {
                ImprovementCaseStatus current = statusOf(row);
                if (current.terminal()) {
                    throw invalid("已形成终态的改进闭环不能重新认领，请新建后续信号");
                }
                row.setOwnerId(owner);
                row.setSlaDueAtMs(request.slaDueAtMs());
                row.setSourceSignalCount(value(source.getSignalCount()));
                row.setUpdatedAtMs(now);
                caseMapper.updateById(row);
            }
            return toVO(row);
        });
    }

    public ImprovementCaseVO createEvalCase(Long id, ImprovementEvalCaseRequest request,
                                            String operator) {
        AgentImprovementCase snapshot = require(id);
        ImprovementSourceType sourceType = ImprovementSourceType.valueOf(snapshot.getSourceType());
        ImprovementSourceFact source = requireSource(sourceType, snapshot.getSourceKey());
        if (!StringUtils.hasText(source.getQuestion())) {
            throw invalid("原始问题缺失，不能构造可复评用例");
        }
        ImprovementSignalGateway gateway = signalGatewayProvider.get();
        if (gateway.evalCaseStore().find(request.evalType(), request.caseId()).isPresent()) {
            throw invalid("评测用例编号已存在：" + request.caseId());
        }
        if (sourceType == ImprovementSourceType.BADCASE) {
            badcaseGatewayProvider.get().adoptAsEvalCase(snapshot.getSourceKey(), request.caseId(),
                request.evalType(), request.expected(), request.category(), operator);
        } else {
            gateway.evalCaseStore().save(new PersistedEvalCase(request.caseId(), request.evalType(),
                source.getQuestion(), request.expected(), request.category(), EvalCaseSource.MANUAL,
                true, "knowledge-gap:" + snapshot.getSourceKey(), System.currentTimeMillis()));
        }
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            assertMutable(row);
            row.setEvalType(request.evalType().name());
            row.setEvalCaseId(request.caseId());
            resetAfterEvalCaseChange(row);
            row.setUpdatedAtMs(System.currentTimeMillis());
            caseMapper.updateById(row);
            return toVO(row);
        });
    }

    public ImprovementCaseVO bindArtifact(Long id, ImprovementBindArtifactRequest request) {
        AgentImprovementCase snapshot = require(id);
        ImprovementSourceFact source = requireSource(
            ImprovementSourceType.valueOf(snapshot.getSourceType()), snapshot.getSourceKey());
        String evalCaseId = StringUtils.hasText(request.evalCaseId())
            ? request.evalCaseId().trim() : source.getEvalCaseId();
        if (!StringUtils.hasText(evalCaseId)
            || signalGatewayProvider.get().evalCaseStore().find(request.evalType(), evalCaseId).isEmpty()) {
            throw invalid("必须绑定一条已存在且同类型的回归用例");
        }
        AiAgent agent = agentMapper.selectById(request.agentId());
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在：" + request.agentId());
        }
        EvalVersionBinding candidate = publisher.previewVersionBinding(request.agentId());
        String candidateJson = write(candidate);
        String artifactVersion = fingerprint(candidate);
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            assertBindable(row);
            row.setAgentId(agent.getId());
            row.setAgentCode(agent.getAgentCode());
            row.setArtifactType(ARTIFACT_TYPE);
            row.setArtifactVersion(artifactVersion);
            row.setCandidateVersionsJson(candidateJson);
            row.setEvalType(request.evalType().name());
            row.setEvalCaseId(evalCaseId);
            resetAfterArtifactChange(row);
            row.setStatus(ImprovementCaseStatus.READY_FOR_REEVALUATION.name());
            row.setUpdatedAtMs(System.currentTimeMillis());
            caseMapper.updateById(row);
            return toVO(row);
        });
    }

    /** 外部评测不占用 Admin 数据库事务；开始与完成分别用短事务冻结同一候选。 */
    public ImprovementCaseVO reevaluate(Long id, String remark) {
        AgentImprovementCase started = transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            ImprovementCaseStatus current = statusOf(row);
            if (current != ImprovementCaseStatus.READY_FOR_REEVALUATION
                && current != ImprovementCaseStatus.REEVALUATION_FAILED) {
                throw invalid("当前状态不允许复评：" + current);
            }
            row.setStatus(ImprovementCaseStatus.REEVALUATING.name());
            row.setReevaluationStatus(ImprovementReevaluationStatus.RUNNING.name());
            row.setReevaluationError(null);
            row.setUpdatedAtMs(System.currentTimeMillis());
            caseMapper.updateById(row);
            return row;
        });
        try {
            EvalComparison comparison = evalAdminService.trigger(
                EvalType.valueOf(started.getEvalType()), remark);
            return completeReevaluation(id, comparison);
        } catch (RuntimeException e) {
            markReevaluationFailed(id, e);
            throw e;
        }
    }

    public ImprovementCaseVO publish(Long id) {
        AgentImprovementCase snapshot = require(id);
        EvalVersionBinding currentCandidate = publisher.previewVersionBinding(snapshot.getAgentId());
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            if (statusOf(row) != ImprovementCaseStatus.READY_TO_PUBLISH
                || reevaluationOf(row) != ImprovementReevaluationStatus.PASSED) {
                throw invalid("只有复评通过的候选才能发布");
            }
            EvalVersionBinding evaluatedCandidate = readBinding(row.getCandidateVersionsJson());
            if (!Objects.equals(evaluatedCandidate, currentCandidate)
                || !Objects.equals(row.getArtifactVersion(), fingerprint(currentCandidate))) {
                throw invalid("Agent 候选已变化，请重新绑定制品并复评");
            }
            String taskId = publishTaskService.enqueueAgent(row.getAgentId());
            row.setPublishTaskId(taskId);
            row.setPublishStatus(RuntimePublishStatus.PENDING.name());
            row.setStatus(ImprovementCaseStatus.PUBLISHING.name());
            row.setEffectStatus(ImprovementEffectStatus.NOT_STARTED.name());
            row.setNextActionAtMs(System.currentTimeMillis());
            row.setLeaseOwner(null);
            row.setLeaseUntilMs(0L);
            row.setLastError(null);
            row.setUpdatedAtMs(System.currentTimeMillis());
            caseMapper.updateById(row);
            return toVO(row);
        });
    }

    /** 手工催一次状态同步；真正处理仍由数据库租约 Worker 完成。 */
    public ImprovementCaseVO scheduleRefresh(Long id) {
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            ImprovementCaseStatus current = statusOf(row);
            if (current != ImprovementCaseStatus.PUBLISHING
                && current != ImprovementCaseStatus.OBSERVING) {
                throw invalid("当前状态无需刷新：" + current);
            }
            row.setNextActionAtMs(System.currentTimeMillis());
            row.setUpdatedAtMs(System.currentTimeMillis());
            caseMapper.updateById(row);
            return toVO(row);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void processAutomation(AgentImprovementCase claimed) {
        AgentImprovementCase row = lock(claimed.getId());
        if (!Objects.equals(row.getLeaseOwner(), claimed.getLeaseOwner())) {
            return;
        }
        ImprovementCaseStatus status = statusOf(row);
        if (status == ImprovementCaseStatus.PUBLISHING) {
            refreshPublish(row);
        } else if (status == ImprovementCaseStatus.OBSERVING) {
            observe(row);
        }
        row.setLeaseOwner(null);
        row.setLeaseUntilMs(0L);
        row.setAutomationFailures(0);
        if (statusOf(row) != ImprovementCaseStatus.PUBLISH_FAILED) {
            row.setLastError(null);
        }
        row.setUpdatedAtMs(System.currentTimeMillis());
        caseMapper.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAutomationFailure(AgentImprovementCase claimed, Throwable failure) {
        AgentImprovementCase row = lock(claimed.getId());
        if (!Objects.equals(row.getLeaseOwner(), claimed.getLeaseOwner())) {
            return;
        }
        int failures = value(row.getAutomationFailures()) + 1;
        long base = Math.max(1000L, properties.getScanIntervalMs());
        long shifted = base * (1L << Math.min(failures, 8));
        row.setAutomationFailures(failures);
        row.setLastError(errorMessage(failure));
        row.setNextActionAtMs(System.currentTimeMillis()
            + Math.min(shifted, Math.max(base, properties.getMaxBackoffMs())));
        row.setLeaseOwner(null);
        row.setLeaseUntilMs(0L);
        row.setUpdatedAtMs(System.currentTimeMillis());
        caseMapper.updateById(row);
    }

    private ImprovementCaseVO completeReevaluation(Long id, EvalComparison comparison) {
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            if (statusOf(row) != ImprovementCaseStatus.REEVALUATING) {
                throw invalid("复评候选已被其他操作改变");
            }
            List<String> failures = reevaluationFailures(row, comparison);
            boolean passed = failures.isEmpty();
            row.setEvalRunId(comparison.current().runId());
            row.setReevaluationVerdict(comparison.verdict().name());
            row.setReevaluationStatus((passed
                ? ImprovementReevaluationStatus.PASSED
                : ImprovementReevaluationStatus.FAILED).name());
            row.setReevaluationError(passed ? null : truncate(String.join("；", failures)));
            row.setStatus((passed
                ? ImprovementCaseStatus.READY_TO_PUBLISH
                : ImprovementCaseStatus.REEVALUATION_FAILED).name());
            row.setUpdatedAtMs(System.currentTimeMillis());
            caseMapper.updateById(row);
            return toVO(row);
        });
    }

    private List<String> reevaluationFailures(AgentImprovementCase row, EvalComparison comparison) {
        List<String> failures = new ArrayList<>();
        if (comparison == null || comparison.current() == null) {
            return List.of("复评未返回运行事实");
        }
        if (!comparison.current().gatePassed()) {
            failures.add("Judge 或评测运行不完整");
        }
        EvalVersionBinding actual = comparison.current().versionBinding();
        EvalVersionBinding candidate = readBinding(row.getCandidateVersionsJson());
        if (actual == null || !actual.isComplete() || !actual.matchesCandidate(candidate)) {
            failures.add("复评运行的制品版本与候选不一致");
        }
        if (comparison.current().failedCaseIds().contains(row.getEvalCaseId())) {
            failures.add("目标回归用例仍失败：" + row.getEvalCaseId());
        }
        if (!comparison.regressions().isEmpty()) {
            failures.add("出现新增回归：" + comparison.regressions());
        }
        if (comparison.datasetChanged()) {
            failures.add("评测集规模变化，不能与基线直接比较");
        }
        return failures;
    }

    private void markReevaluationFailed(Long id, Throwable failure) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentImprovementCase row = lock(id);
            if (statusOf(row) == ImprovementCaseStatus.REEVALUATING) {
                row.setStatus(ImprovementCaseStatus.REEVALUATION_FAILED.name());
                row.setReevaluationStatus(ImprovementReevaluationStatus.FAILED.name());
                row.setReevaluationError(errorMessage(failure));
                row.setUpdatedAtMs(System.currentTimeMillis());
                caseMapper.updateById(row);
            }
        });
    }

    private void refreshPublish(AgentImprovementCase row) {
        RuntimePublishTask task = publishTaskMapper.selectById(row.getPublishTaskId());
        if (task == null) {
            throw new IllegalStateException("runtime publish task not found: " + row.getPublishTaskId());
        }
        RuntimePublishStatus publishStatus = RuntimePublishStatus.valueOf(task.getStatus());
        row.setPublishStatus(publishStatus.name());
        row.setPublishRevision(task.getRevision());
        long now = System.currentTimeMillis();
        if (publishStatus == RuntimePublishStatus.APPLIED) {
            if (!StringUtils.hasText(task.getRevision())) {
                throw new IllegalStateException("applied runtime publish task revision is missing");
            }
            row.setBaselineSignalCount(currentSignalCount(row));
            row.setPublishedAtMs(now);
            row.setObservationStartedAtMs(now);
            row.setObservationEndsAtMs(now + Math.max(1000L, properties.getObservationWindowMs()));
            row.setMinExposureCalls(Math.max(1, properties.getMinExposureCalls()));
            row.setMaxRecurrenceSignals(Math.max(0, properties.getMaxRecurrenceSignals()));
            row.setObservedCalls(0L);
            row.setObservedSignals(0L);
            row.setEffectStatus(ImprovementEffectStatus.OBSERVING.name());
            row.setStatus(ImprovementCaseStatus.OBSERVING.name());
            row.setNextActionAtMs(now);
        } else if (publishStatus.isAdvancing()) {
            // Worker 或实例 ACK 会把它继续往前推，排下一次扫描等它走完
            row.setNextActionAtMs(now + Math.max(1000L, properties.getScanIntervalMs()));
        } else {
            // 剩下的只有 BLOCKED（门禁阻断，等重评或紧急豁免）与 FAILED / SUPERSEDED，
            // 共同点是"不会自己往前走"，所以一律停掉轮询并把失败原因抬到面板上。
            //
            // BLOCKED 此前落在"继续轮询"那一支，是本方法真正的缺陷所在：调度器的
            // findDueCandidates 只认 PENDING 与租约过期的 PROCESSING，永远不会再捞 BLOCKED，
            // 于是这条 case 每个扫描周期被捞一次、判一次、再排下一次——状态永远停在 PUBLISHING，
            // 又因为没走失败分支，lastError 恒为空、面板的错误提示条不显示，
            // 运营只能看到"发布中"挂着不动，直到 SLA 逾期才冒出一个误导性的"责任人拖了"。
            //
            // 置为非终态的 PUBLISH_FAILED：重评（retryGateBlocked）或豁免（overrideGateBlocked）
            // 之后会新建发布任务重新驱动本 case，面板上它也仍是可再次操作的状态。
            // 门禁失败摘要由 recordGateDecision 写进 last_error，与发布失败共用同一个字段。
            row.setStatus(ImprovementCaseStatus.PUBLISH_FAILED.name());
            row.setNextActionAtMs(NO_ACTION_AT);
            row.setLastError(truncate(task.getLastError()));
        }
    }

    private void observe(AgentImprovementCase row) {
        long now = System.currentTimeMillis();
        long end = Math.min(now + 1, row.getObservationEndsAtMs() + 1);
        ImprovementSignalGateway gateway = signalGatewayProvider.get();
        long calls = gateway.signalMapper().exposureCalls(row.getTenantId(), row.getPublishRevision(),
            row.getObservationStartedAtMs(), end);
        long signals = Math.max(0L, currentSignalCount(row) - value(row.getBaselineSignalCount()));
        row.setObservedCalls(calls);
        row.setObservedSignals(signals);
        row.setLastObservedAtMs(now);
        if (signals > value(row.getMaxRecurrenceSignals())) {
            row.setEffectStatus(ImprovementEffectStatus.INEFFECTIVE.name());
            row.setStatus(ImprovementCaseStatus.INEFFECTIVE.name());
            row.setNextActionAtMs(NO_ACTION_AT);
            return;
        }
        if (now >= row.getObservationEndsAtMs()) {
            if (calls < value(row.getMinExposureCalls())) {
                row.setEffectStatus(ImprovementEffectStatus.INCONCLUSIVE.name());
                row.setStatus(ImprovementCaseStatus.INCONCLUSIVE.name());
            } else {
                row.setEffectStatus(ImprovementEffectStatus.EFFECTIVE.name());
                row.setStatus(ImprovementCaseStatus.VERIFIED.name());
            }
            row.setNextActionAtMs(NO_ACTION_AT);
            return;
        }
        row.setNextActionAtMs(now + Math.max(1000L, properties.getScanIntervalMs()));
    }

    private long currentSignalCount(AgentImprovementCase row) {
        ImprovementSignalGateway gateway = signalGatewayProvider.get();
        return ImprovementSourceType.KNOWLEDGE_GAP.name().equals(row.getSourceType())
            ? gateway.signalMapper().knowledgeGapSignalCount(row.getTenantId(), row.getSignalHash())
            : gateway.signalMapper().badcaseSignalCount(row.getTenantId(), row.getSignalHash());
    }

    private ImprovementSourceFact requireSource(ImprovementSourceType type, String sourceKey) {
        if (!StringUtils.hasText(sourceKey)) {
            throw invalid("原始信号键不能为空");
        }
        String tenantId = TenantContext.require();
        ImprovementSourceFact fact = type == ImprovementSourceType.KNOWLEDGE_GAP
            ? signalGatewayProvider.get().signalMapper().findKnowledgeGap(tenantId, sourceKey)
            : signalGatewayProvider.get().signalMapper().findBadcase(tenantId, sourceKey);
        if (fact == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "原始改进信号不存在：" + sourceKey);
        }
        if (!StringUtils.hasText(fact.getSignalHash())) {
            throw invalid("原始问题缺失，无法建立上线复发观测键");
        }
        return fact;
    }

    private AgentImprovementCase require(Long id) {
        AgentImprovementCase row = caseMapper.selectById(id);
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "改进闭环不存在：" + id);
        }
        return row;
    }

    private AgentImprovementCase lock(Long id) {
        AgentImprovementCase row = caseMapper.lockById(id);
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "改进闭环不存在：" + id);
        }
        return row;
    }

    private void assertMutable(AgentImprovementCase row) {
        ImprovementCaseStatus status = statusOf(row);
        if (status == ImprovementCaseStatus.PUBLISHING || status == ImprovementCaseStatus.OBSERVING
            || status == ImprovementCaseStatus.VERIFIED || status == ImprovementCaseStatus.CANCELLED) {
            throw invalid("当前状态不能更换评测用例：" + status);
        }
    }

    private void assertBindable(AgentImprovementCase row) {
        ImprovementCaseStatus status = statusOf(row);
        if (status == ImprovementCaseStatus.PUBLISHING || status == ImprovementCaseStatus.OBSERVING
            || status == ImprovementCaseStatus.REEVALUATING
            || status == ImprovementCaseStatus.VERIFIED || status == ImprovementCaseStatus.CANCELLED) {
            throw invalid("当前状态不能绑定新候选：" + status);
        }
    }

    private void resetAfterEvalCaseChange(AgentImprovementCase row) {
        row.setEvalRunId(null);
        row.setReevaluationStatus(ImprovementReevaluationStatus.NOT_RUN.name());
        row.setReevaluationVerdict(null);
        row.setReevaluationError(null);
        if (StringUtils.hasText(row.getCandidateVersionsJson())) {
            row.setStatus(ImprovementCaseStatus.READY_FOR_REEVALUATION.name());
        }
        resetPublishAndObservation(row);
    }

    private void resetAfterArtifactChange(AgentImprovementCase row) {
        row.setEvalRunId(null);
        row.setReevaluationStatus(ImprovementReevaluationStatus.NOT_RUN.name());
        row.setReevaluationVerdict(null);
        row.setReevaluationError(null);
        resetPublishAndObservation(row);
    }

    private void resetPublishAndObservation(AgentImprovementCase row) {
        row.setPublishTaskId(null);
        row.setPublishRevision(null);
        row.setPublishStatus(null);
        row.setPublishedAtMs(null);
        row.setBaselineSignalCount(null);
        row.setObservationStartedAtMs(null);
        row.setObservationEndsAtMs(null);
        row.setMinExposureCalls(null);
        row.setMaxRecurrenceSignals(null);
        row.setObservedCalls(0L);
        row.setObservedSignals(0L);
        row.setEffectStatus(ImprovementEffectStatus.NOT_STARTED.name());
        row.setLastObservedAtMs(null);
        row.setNextActionAtMs(NO_ACTION_AT);
        row.setLeaseOwner(null);
        row.setLeaseUntilMs(0L);
        row.setAutomationFailures(0);
        row.setLastError(null);
    }

    private ImprovementCaseVO toVO(AgentImprovementCase row) {
        long now = System.currentTimeMillis();
        ImprovementCaseStatus status = statusOf(row);
        ImprovementSlaStatus slaStatus = status.terminal()
            ? ImprovementSlaStatus.CLOSED
            : now > row.getSlaDueAtMs() ? ImprovementSlaStatus.OVERDUE : ImprovementSlaStatus.ON_TRACK;
        return new ImprovementCaseVO(
            row.getId(), ImprovementSourceType.valueOf(row.getSourceType()), row.getSourceKey(),
            value(row.getSourceSignalCount()), row.getOwnerId(), value(row.getSlaDueAtMs()), slaStatus,
            slaStatus == ImprovementSlaStatus.OVERDUE ? now - row.getSlaDueAtMs() : 0L,
            status, row.getAgentId(), row.getAgentCode(), row.getArtifactType(), row.getArtifactVersion(),
            readBinding(row.getCandidateVersionsJson()),
            StringUtils.hasText(row.getEvalType()) ? EvalType.valueOf(row.getEvalType()) : null,
            row.getEvalCaseId(), row.getEvalRunId(), reevaluationOf(row), row.getReevaluationVerdict(),
            row.getReevaluationError(), row.getPublishTaskId(), row.getPublishRevision(), row.getPublishStatus(),
            row.getPublishedAtMs(), row.getObservationStartedAtMs(), row.getObservationEndsAtMs(),
            row.getMinExposureCalls(), row.getMaxRecurrenceSignals(), value(row.getObservedCalls()),
            value(row.getObservedSignals()), ImprovementEffectStatus.valueOf(row.getEffectStatus()),
            row.getLastObservedAtMs(), row.getLastError(), value(row.getCreatedAtMs()), value(row.getUpdatedAtMs()));
    }

    private ImprovementCaseStatus statusOf(AgentImprovementCase row) {
        return ImprovementCaseStatus.valueOf(row.getStatus());
    }

    private ImprovementReevaluationStatus reevaluationOf(AgentImprovementCase row) {
        return ImprovementReevaluationStatus.valueOf(row.getReevaluationStatus());
    }

    private String fingerprint(EvalVersionBinding binding) {
        return EvalFingerprint.of("agent-improvement-runtime-v1", binding.datasetVersion(),
            binding.datasetFingerprint(), binding.modelVersion(), binding.promptVersion(),
            binding.agentVersion(), binding.knowledgeBaseVersion(), binding.toolVersion(),
            binding.judgeVersion(), binding.rubricVersion());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("improvement evidence serialization failed", e);
        }
    }

    private EvalVersionBinding readBinding(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, EvalVersionBinding.class);
        } catch (Exception e) {
            throw new IllegalStateException("improvement candidate version evidence is invalid", e);
        }
    }

    private BizException invalid(String message) {
        return new BizException(ResultCode.PARAM_INVALID, message);
    }

    private String errorMessage(Throwable failure) {
        String message = failure.getMessage() == null
            ? failure.getClass().getSimpleName() : failure.getMessage();
        return truncate(message);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
