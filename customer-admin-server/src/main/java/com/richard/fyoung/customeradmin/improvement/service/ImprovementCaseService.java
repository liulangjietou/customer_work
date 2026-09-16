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
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
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
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateEvaluation;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateBindRequest;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateReviewVO;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidatePublishRequest;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateBindingService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateEvaluationService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidatePublicationService;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationConflictException;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationReceipt;
import com.richard.fyoung.customerwork.capability.badcase.Badcase;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseSource;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customeradmin.tenant.TenantSqlConditions;
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
import java.util.UUID;

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
    private final EvalDatasetAdminService evalDatasetAdminService;
    private final KnowledgeCandidateBindingService knowledgeBindings;
    private final KnowledgeCandidateEvaluationService knowledgeEvaluations;
    private final KnowledgeCandidatePublicationService knowledgePublications;
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
                                  EvalDatasetAdminService evalDatasetAdminService,
                                  KnowledgeCandidateBindingService knowledgeBindings,
                                  KnowledgeCandidateEvaluationService knowledgeEvaluations,
                                  KnowledgeCandidatePublicationService knowledgePublications,
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
        this.evalDatasetAdminService = evalDatasetAdminService;
        this.knowledgeBindings = knowledgeBindings;
        this.knowledgeEvaluations = knowledgeEvaluations;
        this.knowledgePublications = knowledgePublications;
        this.publishTaskService = publishTaskService;
        this.publishTaskMapper = publishTaskMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<ImprovementCaseVO> findBySource(ImprovementSourceType sourceType, String sourceKey) {
        AgentImprovementCase row = caseMapper.selectOne(sourceQuery(sourceType, sourceKey));
        return Optional.ofNullable(row).map(this::toVO);
    }

    /** 查询与认领共用精确身份条件；普通等值条件保留索引筛选，CAST 防止排序规则扩大匹配。 */
    private LambdaQueryWrapper<AgentImprovementCase> sourceQuery(ImprovementSourceType sourceType, String sourceKey) {
        String tenant = TenantContext.require();
        return new LambdaQueryWrapper<AgentImprovementCase>()
            .eq(AgentImprovementCase::getTenantId, tenant)
            .apply(TenantSqlConditions.EXACT_TENANT, tenant)
            .eq(AgentImprovementCase::getSourceType, sourceType.name())
            .eq(AgentImprovementCase::getSourceKey, sourceKey)
            .apply("CAST(source_key AS BINARY) = CAST({0} AS BINARY)", sourceKey);
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
            // uk_improvement_source 已保证至多一行；无需 LIMIT，避免解析器把锁子句重排成非法 SQL。
            AgentImprovementCase row = caseMapper.selectOne(sourceQuery(sourceType, sourceKey)
                .last("FOR UPDATE"));
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

    /**
     * 先锁定改进项并确认状态，再创建独立可追溯的客服端用例；避免被拒绝的操作留下额外用例。
     * Admin 行锁覆盖创建与绑定，阻止同一改进项在两步之间进入复评或发布。
     */
    public ImprovementCaseVO createEvalCase(Long id, ImprovementEvalCaseRequest request,
                                            String operator) {
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            assertMutable(row);
            ImprovementSourceType sourceType = ImprovementSourceType.valueOf(row.getSourceType());
            ImprovementSourceFact source = requireSource(sourceType, row.getSourceKey());
            if (!StringUtils.hasText(source.getQuestion())) {
                throw invalid("原始问题缺失，不能构造可复评用例");
            }
            ImprovementSignalGateway gateway = signalGatewayProvider.get();
            if (gateway.evalCaseStore().find(request.evalType(), request.caseId()).isPresent()) {
                throw invalid("评测用例编号已存在：" + request.caseId());
            }
            if (sourceType == ImprovementSourceType.BADCASE) {
                badcaseGatewayProvider.get().adoptAsEvalCase(row.getSourceKey(), request.caseId(),
                    request.evalType(), request.expected(), request.category(), operator);
            } else {
                gateway.evalCaseStore().save(new PersistedEvalCase(request.caseId(), request.evalType(),
                    source.getQuestion(), request.expected(), request.category(), EvalCaseSource.MANUAL,
                    true, "knowledge-gap:" + row.getSourceKey(), System.currentTimeMillis()));
            }
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

    /** 知识候选使用已有改进状态机，但绑定的是冻结 FAQ 和审核用例，不借用运行配置的指纹。 */
    public ImprovementCaseVO bindKnowledgeCandidate(Long id, KnowledgeCandidateBindRequest request, long actor) {
        AgentImprovementCase snapshot = require(id);
        requireKnowledgeSource(snapshot);
        KnowledgeCandidateBinding binding = knowledgeBindings.prepare(id, snapshot.getSourceKey(), request);
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            requireKnowledgeSource(row);
            if (!Objects.equals(snapshot.getSourceKey(), row.getSourceKey())) throw invalid("改进问题已变化");
            // 响应丢失后的同版本重试只回读，不能清掉其后完成的评测或已开始的发布。
            if (isKnowledgeCandidate(row) && Objects.equals(row.getArtifactVersion(), binding.fingerprint())
                && bindingMatchesParent(row, binding) && statusOf(row) != ImprovementCaseStatus.PUBLISH_FAILED) return toVO(row);
            assertBindable(row);
            knowledgeBindings.save(binding, actor);
            row.setAgentId(binding.agentId());
            row.setAgentCode(binding.agentCode());
            row.setArtifactType(KnowledgeCandidateBinding.ARTIFACT_TYPE);
            row.setArtifactVersion(binding.fingerprint());
            row.setCandidateVersionsJson(write(binding.versions()));
            row.setEvalType(EvalType.QUALITY.name());
            row.setEvalCaseId(binding.targetCaseId());
            resetAfterArtifactChange(row);
            row.setStatus(ImprovementCaseStatus.READY_FOR_REEVALUATION.name());
            row.setUpdatedAtMs(System.currentTimeMillis());
            caseMapper.updateById(row);
            return toVO(row);
        });
    }

    /** 只展示当前改进项引用的知识证据，不将冻结的整库正文作为 API 响应。 */
    public KnowledgeCandidateReviewVO knowledgeCandidateReview(Long id) {
        AgentImprovementCase row = require(id);
        requireKnowledgeSource(row);
        if (!isKnowledgeCandidate(row)) return null;
        return knowledgeEvaluations.review(knowledgeBindings.require(id, row.getArtifactVersion()), row.getEvalRunId());
    }

    /** 专用入口执行候选知识对照，不调用客服端当前正式配置的全局评测。 */
    public ImprovementCaseVO reevaluateKnowledgeCandidate(Long id, String remark) {
        AgentImprovementCase started = startReevaluation(id, true);
        String attemptId = started.getReevaluationAttemptId();
        long deadlineAtMs = started.getReevaluationDeadlineAtMs();
        try {
            KnowledgeCandidateBinding binding = requireKnowledgeBinding(started);
            KnowledgeCandidateEvaluation evaluation = knowledgeEvaluations.run(binding, started.getSourceKey(), remark, deadlineAtMs);
            List<String> failures = new ArrayList<>(knowledgeEvaluations.failures(binding, evaluation));
            try {
                knowledgeBindings.requireCurrent(binding, started.getSourceKey());
            } catch (BizException changed) {
                failures.add(changed.getMessage());
            }
            return transactionTemplate.execute(status -> {
                AgentImprovementCase row = lock(id);
                requireKnowledgeSource(row);
                requireRunningReevaluation(row, attemptId);
                if (!Objects.equals(row.getArtifactVersion(), binding.fingerprint())) {
                    throw invalid("复评候选已被其他操作改变");
                }
                knowledgeEvaluations.save(evaluation);
                applyReevaluation(row, evaluation.comparison(), failures);
                caseMapper.updateById(row);
                return toVO(row);
            });
        } catch (RuntimeException e) {
            markReevaluationFailed(id, attemptId, e);
            throw e;
        }
    }

    /** 外部评测不占用 Admin 数据库事务；开始与完成分别用短事务冻结同一候选。 */
    public ImprovementCaseVO reevaluate(Long id, String remark) {
        AgentImprovementCase started = startReevaluation(id, false);
        String attemptId = started.getReevaluationAttemptId();
        try {
            EvalComparison comparison = evalAdminService.trigger(
                EvalType.valueOf(started.getEvalType()), remark);
            return completeReevaluation(id, attemptId, comparison);
        } catch (RuntimeException e) {
            markReevaluationFailed(id, attemptId, e);
            throw e;
        }
    }

    private AgentImprovementCase startReevaluation(Long id, boolean knowledge) {
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            if (knowledge != isKnowledgeCandidate(row)) throw invalid("请使用与候选制品对应的复评入口");
            if (knowledge) requireKnowledgeSource(row);
            ImprovementCaseStatus current = statusOf(row);
            if (current != ImprovementCaseStatus.READY_FOR_REEVALUATION
                && current != ImprovementCaseStatus.REEVALUATION_FAILED) {
                throw invalid("当前状态不允许复评：" + current);
            }
            long now = System.currentTimeMillis();
            row.beginReevaluation(UUID.randomUUID().toString(),
                Math.addExact(now, Math.max(1000L, properties.getReevaluationTimeoutMs())), now);
            caseMapper.updateById(row);
            return row;
        });
    }

    public ImprovementCaseVO publish(Long id) {
        AgentImprovementCase snapshot = require(id);
        if (isKnowledgeCandidate(snapshot)) throw invalid("知识候选必须使用知识发布入口");
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
            requirePublishEvidence(row);
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

    /** 父记录即持久化发布意图，和候选冻结同库提交；真正 FAQ 副作用由 Worker 重放。 */
    public ImprovementCaseVO publishKnowledgeCandidate(Long id, KnowledgeCandidatePublishRequest request, long actor) {
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            requireKnowledgeSource(row);
            if (!isKnowledgeCandidate(row)) throw invalid("当前改进项没有绑定知识候选");
            if (!Objects.equals(row.getArtifactVersion(), request.expectedArtifactFingerprint())
                || !Objects.equals(row.getEvalRunId(), request.expectedEvaluationRunId())) {
                throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "候选或评测结果已变化，请重新核对待发布正文");
            }
            if (statusOf(row) == ImprovementCaseStatus.PUBLISHING || statusOf(row) == ImprovementCaseStatus.PUBLISHED) {
                return toVO(row);
            }
            if (statusOf(row) != ImprovementCaseStatus.READY_TO_PUBLISH
                || reevaluationOf(row) != ImprovementReevaluationStatus.PASSED) {
                throw invalid("只有实际复评通过的知识候选才能发布");
            }
            knowledgePublications.reserve(requireKnowledgeBinding(row), row.getSourceKey(), row.getEvalRunId());
            row.setPublishTaskId(UUID.randomUUID().toString());
            row.setPublishRequestedBy(actor);
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
            if (isKnowledgeCandidate(row)) refreshKnowledgePublish(row);
            else refreshPublish(row);
        } else if (status == ImprovementCaseStatus.OBSERVING) {
            observe(row);
        } else if (status == ImprovementCaseStatus.REEVALUATING) {
            long now = System.currentTimeMillis();
            if (row.reevaluationExpired(now)) {
                row.failReevaluation(row.getReevaluationAttemptId(), AgentImprovementCase.REEVALUATION_TIMEOUT_MESSAGE, now);
            } else {
                row.setNextActionAtMs(row.getReevaluationDeadlineAtMs());
            }
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

    private ImprovementCaseVO completeReevaluation(Long id, String attemptId, EvalComparison comparison) {
        return transactionTemplate.execute(status -> {
            AgentImprovementCase row = lock(id);
            requireRunningReevaluation(row, attemptId);
            List<String> failures = reevaluationFailures(row, comparison);
            applyReevaluation(row, comparison, failures);
            caseMapper.updateById(row);
            return toVO(row);
        });
    }

    private void applyReevaluation(AgentImprovementCase row, EvalComparison comparison, List<String> failures) {
        boolean passed = failures.isEmpty();
        row.setEvalRunId(comparison.current().runId());
        row.setReevaluationVerdict(comparison.verdict().name());
        row.setReevaluationStatus((passed ? ImprovementReevaluationStatus.PASSED : ImprovementReevaluationStatus.FAILED).name());
        row.setReevaluationError(passed ? null : truncate(String.join("；", failures)));
        row.setStatus((passed ? ImprovementCaseStatus.READY_TO_PUBLISH : ImprovementCaseStatus.REEVALUATION_FAILED).name());
        row.setNextActionAtMs(NO_ACTION_AT);
        row.setLeaseOwner(null);
        row.setLeaseUntilMs(0L);
        row.setUpdatedAtMs(System.currentTimeMillis());
    }

    private boolean isKnowledgeCandidate(AgentImprovementCase row) {
        return KnowledgeCandidateBinding.ARTIFACT_TYPE.equals(row.getArtifactType());
    }

    private void requireKnowledgeSource(AgentImprovementCase row) {
        if (!Objects.equals(TenantContext.require(), row.getTenantId())
            || !ImprovementSourceType.KNOWLEDGE_GAP.name().equals(row.getSourceType())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识改进项不存在");
        }
    }

    private KnowledgeCandidateBinding requireKnowledgeBinding(AgentImprovementCase row) {
        requireKnowledgeSource(row);
        KnowledgeCandidateBinding binding = knowledgeBindings.require(row.getId(), row.getArtifactVersion());
        if (!bindingMatchesParent(row, binding)) {
            throw invalid("知识回归用例或版本已变化，请重新绑定候选");
        }
        return binding;
    }

    private boolean bindingMatchesParent(AgentImprovementCase row, KnowledgeCandidateBinding binding) {
        return Objects.equals(row.getEvalCaseId(), binding.targetCaseId())
            && Objects.equals(row.getAgentId(), binding.agentId()) && Objects.equals(row.getAgentCode(), binding.agentCode())
            && Objects.equals(readBinding(row.getCandidateVersionsJson()), binding.versions())
            && EvalType.QUALITY.name().equals(row.getEvalType());
    }

    private List<String> reevaluationFailures(AgentImprovementCase row, EvalComparison comparison) {
        List<String> failures = new ArrayList<>();
        if (comparison == null || comparison.current() == null) {
            return List.of("复评未返回运行事实");
        }
        if (!comparison.current().gatePassed()) {
            failures.add("Judge 或评测运行不完整");
        }
        try {
            evalDatasetAdminService.requireExecutedCase(comparison.current(),
                EvalType.valueOf(row.getEvalType()), row.getEvalCaseId());
        } catch (BizException e) {
            failures.add(e.getMessage());
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

    /** 历史 PASSED 标记也必须关联可核对的实际运行，不能绕过后续修正的复评门禁。 */
    private void requirePublishEvidence(AgentImprovementCase row) {
        if (!StringUtils.hasText(row.getEvalRunId())) {
            throw invalid("缺少已通过的评测运行记录，请重新绑定候选并复评");
        }
        EvalComparison comparison = evalAdminService.comparison(row.getEvalRunId());
        if (comparison == null || comparison.current() == null
            || !Objects.equals(row.getEvalRunId(), comparison.current().runId())) {
            throw invalid("评测运行记录与改进项不一致，请重新绑定候选并复评");
        }
        List<String> failures = reevaluationFailures(row, comparison);
        if (!failures.isEmpty()) {
            throw invalid("复评证据已失效，请重新绑定候选并复评：" + truncate(String.join("；", failures)));
        }
    }

    private void requireRunningReevaluation(AgentImprovementCase row, String attemptId) {
        if (!row.isRunningReevaluation(attemptId)) throw invalid("复评执行已被替换，请核对当前记录");
        if (row.reevaluationExpired(System.currentTimeMillis())) throw invalid(AgentImprovementCase.REEVALUATION_TIMEOUT_MESSAGE);
    }

    private void markReevaluationFailed(Long id, String attemptId, Throwable failure) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentImprovementCase row = lock(id);
            long now = System.currentTimeMillis();
            String error = row.reevaluationExpired(now) ? AgentImprovementCase.REEVALUATION_TIMEOUT_MESSAGE : errorMessage(failure);
            if (row.failReevaluation(attemptId, error, now)) {
                caseMapper.updateById(row);
            }
        });
    }

    private void refreshKnowledgePublish(AgentImprovementCase row) {
        KnowledgeCandidateBinding binding = requireKnowledgeBinding(row);
        if (row.getPublishRequestedBy() == null) throw new IllegalStateException("knowledge publication actor is missing");
        KnowledgePublicationReceipt receipt;
        try {
            receipt = knowledgePublications.publish(binding, row.getSourceKey(), row.getEvalRunId(),
                row.getPublishTaskId(), row.getPublishRequestedBy());
        } catch (BizException | KnowledgePublicationConflictException conflict) {
            knowledgePublications.finish(binding, false);
            row.setPublishStatus(RuntimePublishStatus.FAILED.name());
            row.setStatus(ImprovementCaseStatus.PUBLISH_FAILED.name());
            row.setLastError(errorMessage(conflict));
            row.setNextActionAtMs(NO_ACTION_AT);
            return;
        }
        // 数据库异常不进入上面的已知失败分支，保留原意图供 Worker 核对或重放。
        knowledgePublications.finish(binding, true);
        row.setPublishStatus(RuntimePublishStatus.APPLIED.name());
        row.setPublishRevision("faq/" + receipt.knowledgeId());
        row.setPublishedAtMs(receipt.publishedAtMs());
        row.setStatus(ImprovementCaseStatus.PUBLISHED.name());
        row.setEffectStatus(ImprovementEffectStatus.NOT_STARTED.name());
        row.setNextActionAtMs(NO_ACTION_AT);
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
        String tenant = TenantContext.require();
        AgentImprovementCase row = caseMapper.selectById(id);
        if (row == null || !tenant.equals(row.getTenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "改进闭环不存在：" + id);
        }
        return row;
    }

    private AgentImprovementCase lock(Long id) {
        AgentImprovementCase row = caseMapper.lockById(id, TenantContext.require());
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "改进闭环不存在：" + id);
        }
        return row;
    }

    private void assertMutable(AgentImprovementCase row) {
        ImprovementCaseStatus status = statusOf(row);
        if (status == ImprovementCaseStatus.PUBLISHING || status == ImprovementCaseStatus.OBSERVING
            || status == ImprovementCaseStatus.REEVALUATING
            || status == ImprovementCaseStatus.PUBLISHED
            || status == ImprovementCaseStatus.VERIFIED || status == ImprovementCaseStatus.CANCELLED) {
            throw invalid("当前状态不能更换评测用例：" + status);
        }
    }

    private void assertBindable(AgentImprovementCase row) {
        ImprovementCaseStatus status = statusOf(row);
        if (status == ImprovementCaseStatus.PUBLISHING || status == ImprovementCaseStatus.OBSERVING
            || status == ImprovementCaseStatus.REEVALUATING
            || status == ImprovementCaseStatus.PUBLISHED
            || status == ImprovementCaseStatus.VERIFIED || status == ImprovementCaseStatus.CANCELLED) {
            throw invalid("当前状态不能绑定新候选：" + status);
        }
    }

    private void resetAfterEvalCaseChange(AgentImprovementCase row) {
        row.setReevaluationAttemptId(null);
        row.setReevaluationDeadlineAtMs(null);
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
        row.setReevaluationAttemptId(null);
        row.setReevaluationDeadlineAtMs(null);
        row.setEvalRunId(null);
        row.setReevaluationStatus(ImprovementReevaluationStatus.NOT_RUN.name());
        row.setReevaluationVerdict(null);
        row.setReevaluationError(null);
        resetPublishAndObservation(row);
    }

    private void resetPublishAndObservation(AgentImprovementCase row) {
        row.setPublishTaskId(null);
        row.setPublishRequestedBy(null);
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
            row.getLastObservedAtMs(), row.getLastError(), value(row.getCreatedAtMs()), value(row.getUpdatedAtMs()),
            row.getReevaluationDeadlineAtMs());
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
