package com.richard.fyoung.customeradmin.ops.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeGapReviewDetail;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeGapReviewRequest;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapReviewConflictException;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

/** 复核入口编排；分类与审计在原始信号所在库一次提交，复用现有运营门面。 */
@Service
public class KnowledgeGapReviewService {
    private final OpsGatewayProvider provider;

    public KnowledgeGapReviewService(OpsGatewayProvider provider) {
        this.provider = provider;
    }

    /** 当前租户、分区内查证信号后才返回复核记录。 */
    public KnowledgeGapReviewDetail detail(String questionHash, long beforeRevision) {
        String scope = TenantContext.require();
        var store = provider.get().knowledgeGapReview();
        KnowledgeGap gap = store.find(scope, questionHash).orElseThrow(() ->
            new BizException(ResultCode.RESOURCE_NOT_FOUND, "原始未命中问题不存在"));
        return new KnowledgeGapReviewDetail(gap, store.history(scope, questionHash, beforeRevision));
    }

    /** 仅改变运营分类，不写入知识、评测或发布状态。 */
    public KnowledgeGap review(String questionHash, KnowledgeGapReviewRequest request, String operator) {
        String scope = TenantContext.require();
        var store = provider.get().knowledgeGapReview();
        try {
            return store.review(scope, questionHash, request.expectedRevision(), request.category(),
                request.priority(), request.reason().trim(), operator);
        } catch (NoSuchElementException missing) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "原始未命中问题不存在");
        } catch (KnowledgeGapReviewConflictException conflict) {
            throw new BizException(ResultCode.KNOWLEDGE_GAP_REVIEW_CONFLICT);
        }
    }
}
