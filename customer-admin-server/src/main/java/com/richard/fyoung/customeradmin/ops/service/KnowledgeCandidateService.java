package com.richard.fyoung.customeradmin.ops.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidate;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateStore;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapCategory;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapClassification;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 来源仍由客服库提供事实；候选正文仅写 Admin，不触发正式 FAQ、模型评测或运行配置发布。 */
@Service
public class KnowledgeCandidateService {
    private final KnowledgeCandidateStore store;
    private final OpsGatewayProvider gateway;

    public KnowledgeCandidateService(KnowledgeCandidateStore store, OpsGatewayProvider gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    /** 读取指定来源已保存的候选；空结果表示尚未保存，不隐藏存储故障。 */
    public KnowledgeCandidate bySource(String questionHash) {
        return store.bySource(TenantContext.require(), questionHash).orElse(null);
    }

    /** 只有仍属于本租户且人工确认为知识缺口的当前来源修订可保存候选。 */
    public KnowledgeCandidate save(String id, KnowledgeCandidateSaveRequest request, long actor) {
        String tenant = TenantContext.require();
        requireKnowledgeSource(tenant, request.questionHash(), request.sourceReviewRevision());
        try {
            return store.save(tenant, id, request, actor, System.currentTimeMillis());
        } catch (DuplicateKeyException conflict) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "该问题已有候选，请读取已保存内容后继续编辑");
        }
    }

    /** 冻结或发布只消费当前租户的确切修订，候选编辑和来源重新分类都会使旧绑定失效。 */
    public KnowledgeCandidate requireCurrentVersion(String id, long revision) {
        String tenant = TenantContext.require();
        KnowledgeCandidate candidate = store.find(tenant, id)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识候选不存在"));
        candidate.requireCurrent(revision);
        requireKnowledgeSource(tenant, candidate.questionHash(), candidate.sourceReviewRevision());
        return candidate;
    }

    private void requireKnowledgeSource(String tenant, String questionHash, long sourceReviewRevision) {
        var source = gateway.get().knowledgeGapReview().find(tenant, questionHash)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "原始问题不存在，请重新核对来源"));
        var classification = source.classification();
        if (classification == null || classification.origin() != KnowledgeGapClassification.Origin.MANUAL
            || classification.category() != KnowledgeGapCategory.KNOWLEDGE) {
            throw new BizException(ResultCode.PARAM_INVALID, "请先将原始问题人工复核为知识缺口");
        }
        if (classification.revision() != sourceReviewRevision) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "来源复核已变化，请保留候选正文并重新核对");
        }
    }
}
