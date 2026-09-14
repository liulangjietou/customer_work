package com.richard.fyoung.customeradmin.ops.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateBindRequest;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateBindingStore;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalRunner;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 冻结跨库评测输入；父改进记录的状态锁和事务仍由 ImprovementCaseService 负责。 */
@Service
public class KnowledgeCandidateBindingService {
    private static final int DEFAULT_MAX_ITERS = 10;
    private static final int ENABLED = 1;
    private final KnowledgeCandidateService candidates;
    private final KnowledgeCandidateSnapshotService snapshots;
    private final KnowledgeTrialModelService models;
    private final EvalDatasetAdminService datasets;
    private final AiAgentMapper agents;
    private final KnowledgeCandidateBindingStore store;

    public KnowledgeCandidateBindingService(KnowledgeCandidateService candidates,
        KnowledgeCandidateSnapshotService snapshots, KnowledgeTrialModelService models,
        EvalDatasetAdminService datasets, AiAgentMapper agents, KnowledgeCandidateBindingStore store) {
        this.candidates = candidates;
        this.snapshots = snapshots;
        this.models = models;
        this.datasets = datasets;
        this.agents = agents;
        this.store = store;
    }

    /** 只读准备不占用父记录事务；候选必须来自该改进项的原始问题。 */
    public KnowledgeCandidateBinding prepare(long improvementId, String sourceKey,
                                              KnowledgeCandidateBindRequest request) {
        var candidate = candidates.requireCurrentVersion(request.candidateId(), request.candidateRevision());
        if (!Objects.equals(sourceKey, candidate.questionHash())) {
            throw new BizException(ResultCode.PARAM_INVALID, "知识候选不属于当前改进问题");
        }
        AiAgent agent = requireAgent(request.agentId());
        return new KnowledgeCandidateBinding(improvementId, agent.getId(), agent.getAgentCode(),
            agent.getSystemPrompt(), agent.getMaxIters() == null ? DEFAULT_MAX_ITERS : agent.getMaxIters(),
            snapshots.freeze(request.candidateId(), request.candidateRevision()),
            models.freeze(request.modelDeploymentId()), models.freeze(request.judgeDeploymentId()),
            request.datasetReleaseId(), datasets.requireApprovedQualityCase(request.datasetReleaseId(), request.targetCaseId()),
            request.targetCaseId(), KnowledgeCandidateBinding.CURRENT_TOOL_VERSION, QualityEvalRunner.rubricVersion());
    }

    /** 调用方在持有父记录行锁的 Admin 事务内落库，不独立提交。 */
    public void save(KnowledgeCandidateBinding binding, long actor) {
        requireTenant(binding);
        store.save(binding, actor);
    }

    /** 按父记录及指纹精确回读，不能把别的改进项或租户的高分绑定到当前项。 */
    public KnowledgeCandidateBinding require(long improvementId, String fingerprint) {
        return store.find(TenantContext.require(), improvementId, fingerprint)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识候选绑定不存在"));
    }

    /** 执行前后核对当前输入；模型端点、提示词、审核态、候选及正式知识漂移均须重新绑定。 */
    public void requireCurrent(KnowledgeCandidateBinding binding, String sourceKey) {
        requireTenant(binding);
        var knowledge = binding.knowledge();
        var current = prepare(binding.improvementId(), sourceKey,
            new KnowledgeCandidateBindRequest(knowledge.candidateId(), knowledge.candidateRevision(),
                binding.agentId(), binding.model().deploymentId(), binding.judge().deploymentId(),
                binding.datasetReleaseId(), binding.targetCaseId()));
        if (!binding.fingerprint().equals(current.fingerprint())) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "知识回归输入已变化，请重新绑定并评测");
        }
    }

    private AiAgent requireAgent(Long id) {
        AiAgent agent = agents.selectById(id);
        if (agent == null || !Objects.equals(TenantContext.require(), agent.getTenantId())
            || !Objects.equals(ENABLED, agent.getStatus())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在或已停用");
        }
        if (!StringUtils.hasText(agent.getSystemPrompt())) {
            throw new BizException(ResultCode.PARAM_INVALID, "请先为智能体保存明确的系统提示词");
        }
        return agent;
    }

    private void requireTenant(KnowledgeCandidateBinding binding) {
        if (!Objects.equals(TenantContext.require(), binding.knowledge().tenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识候选绑定不存在");
        }
    }
}
