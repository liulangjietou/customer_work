package com.richard.fyoung.customeradmin.ops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateStore;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationCommand;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationReceipt;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 发布门禁与客服库副作用编排；父改进记录及候选状态仍由调用方的 Admin 事务共同提交。 */
@Service
public class KnowledgeCandidatePublicationService {
    private final KnowledgeCandidateBindingService bindings;
    private final KnowledgeCandidateEvaluationService evaluations;
    private final KnowledgeCandidateStore candidates;
    private final OpsGatewayProvider gateway;
    private final ObjectMapper json;

    public KnowledgeCandidatePublicationService(KnowledgeCandidateBindingService bindings,
        KnowledgeCandidateEvaluationService evaluations, KnowledgeCandidateStore candidates,
        OpsGatewayProvider gateway, ObjectMapper json) {
        this.bindings = bindings; this.evaluations = evaluations; this.candidates = candidates;
        this.gateway = gateway; this.json = json;
    }

    /** 入队前核实实际评测证据，再原子冻结正文；不在这里创建正式 FAQ。 */
    public void reserve(KnowledgeCandidateBinding binding, String sourceKey, String runId) {
        requireReady(binding, sourceKey, runId);
        candidates.reservePublication(TenantContext.require(), binding.knowledge().candidateId(),
            binding.knowledge().candidateRevision());
    }

    /** Worker 重放同一任务时先读成功回执，再核对首次发布的前提，避免把已提交结果误记为失败。 */
    public KnowledgePublicationReceipt publish(KnowledgeCandidateBinding binding, String sourceKey,
                                                String runId, String taskId, long actor) {
        KnowledgePublicationCommand command = command(binding, sourceKey, runId, taskId, actor);
        var publications = gateway.get().knowledgePublication();
        var receipt = publications.find(command);
        if (receipt.isPresent()) return receipt.get();
        requireReady(binding, sourceKey, runId);
        return publications.publish(command);
    }

    /** 只由已确认客服库结果的父状态机调用，未知数据库异常不得解冻候选。 */
    public void finish(KnowledgeCandidateBinding binding, boolean published) {
        candidates.finishPublication(TenantContext.require(), binding.knowledge().candidateId(),
            binding.knowledge().candidateRevision(), published);
    }

    private void requireReady(KnowledgeCandidateBinding binding, String sourceKey, String runId) {
        bindings.requireCurrent(binding, sourceKey);
        var failures = evaluations.failures(binding, evaluations.require(binding, runId));
        if (!failures.isEmpty()) throw new BizException(ResultCode.PARAM_INVALID,
            "知识复评未通过，请重新绑定并评测：" + String.join("；", failures));
    }

    private KnowledgePublicationCommand command(KnowledgeCandidateBinding binding, String sourceKey,
                                                  String runId, String taskId, long actor) {
        if (!Objects.equals(TenantContext.require(), binding.knowledge().tenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识候选绑定不存在");
        }
        try {
            var knowledge = binding.knowledge();
            var entries = Arrays.stream(json.readValue(knowledge.corpusJson(), KnowledgeDO[].class))
                .filter(row -> Objects.equals(row.getId(), knowledge.candidateRowId())).toList();
            if (entries.size() != 1) throw new IllegalStateException("frozen candidate FAQ row is missing or duplicated");
            var candidate = entries.get(0);
            return new KnowledgePublicationCommand(taskId, binding.improvementId(), knowledge.candidateId(),
                knowledge.candidateRevision(), binding.fingerprint(), runId, sourceKey,
                knowledge.sourceReviewRevision(), knowledge.baselineCorpusJson(), candidate.getTitle(),
                candidate.getContent(), candidate.getKeyword(), actor);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to read frozen candidate FAQ", failure);
        }
    }
}
