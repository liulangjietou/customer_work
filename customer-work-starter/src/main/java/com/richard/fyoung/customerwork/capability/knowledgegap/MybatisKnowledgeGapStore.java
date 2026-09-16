package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.capability.knowledgegap.entity.KnowledgeGapDO;
import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeGapEvidence;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis-Plus 知识盲区存储（{@code knowledge-gap.store-mode=jdbc} 时装配）。
 *
 * <p>记录未命中是旁路统计，写失败只记日志；运营查询必须传播读取失败，
 * 避免把数据不可用误当成没有待处理问题。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisKnowledgeGapStore implements KnowledgeGapStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisKnowledgeGapStore.class);

    private final KnowledgeGapMapper mapper;

    public MybatisKnowledgeGapStore(KnowledgeGapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void recordMiss(String question, String scopeId, long nowMs) {
        recordMiss(question, scopeId, nowMs, null);
    }

    @Override
    public void recordMiss(String question, String scopeId, long nowMs, KnowledgeGapEvidence evidence) {
        String normalized = KnowledgeGap.normalize(question);
        if (normalized.isEmpty()) {
            return;
        }
        try {
            KnowledgeGapDO row = new KnowledgeGapDO();
            row.setQuestionHash(KnowledgeGap.hashOf(normalized));
            row.setQuestion(normalized);
            row.setScopeId(scopeId);
            row.setFirstSeenAtMs(nowMs);
            row.setLastSeenAtMs(nowMs);
            var classification = KnowledgeGapClassification.suggest(question);
            row.setCategory(classification.category().name());
            row.setPriority(classification.priority().name());
            row.setClassificationOrigin(classification.origin().name());
            row.setClassificationReason(classification.reason());
            if (evidence != null) {
                row.setRetrievalPath(evidence.path().name());
                row.setSourceAgentCode(evidence.agentCode());
                row.setSourceChannelCode(evidence.channelCode());
                row.setSourceSessionType(evidence.sessionType());
                row.setRetrievalResult(evidence.retrievalResult().name());
            }
            mapper.upsertMiss(row);
        } catch (Exception e) {
            log.error("[MybatisKnowledgeGapStore] recordMiss failed, errorCode={}, scopeId={}",
                "KNOWLEDGE-GAP-SAVE-FAIL", scopeId, e);
        }
    }

    @Override
    public List<KnowledgeGap> topGaps(String scopeId, int limit) {
        return toDomain(mapper.selectTopGaps(scopeId, limit));
    }

    @Override
    public List<KnowledgeGap> topGaps(String scopeId, int limit, KnowledgeGapView view) {
        return toDomain(mapper.selectReviewedGaps(scopeId, limit, view.name()));
    }

    @Override
    public List<KnowledgeGap> findAll(String scopeId) {
        return toDomain(mapper.selectByScope(scopeId));
    }

    private List<KnowledgeGap> toDomain(List<KnowledgeGapDO> rows) {
        List<KnowledgeGap> result = new ArrayList<>(rows.size());
        for (KnowledgeGapDO row : rows) result.add(toDomain(row));
        return result;
    }
    /** 计数查询和人工复核共用同一快照转换，历史来源缺失不会编造。 */
    public static KnowledgeGap toDomain(KnowledgeGapDO row) {
        KnowledgeGapEvidence evidence = row.getRetrievalPath() == null ? null
            : new KnowledgeGapEvidence(KnowledgeGapEvidence.Path.valueOf(row.getRetrievalPath()),
                row.getSourceAgentCode(), row.getSourceChannelCode(), row.getSourceSessionType(),
                KnowledgeGapEvidence.RetrievalResult.valueOf(row.getRetrievalResult()));
        KnowledgeGapClassification classification = row.getCategory() == null
            ? KnowledgeGapClassification.suggestFromStoredQuestion(row.getQuestion())
            : new KnowledgeGapClassification(KnowledgeGapCategory.valueOf(row.getCategory()),
                KnowledgeGapPriority.valueOf(row.getPriority()),
                KnowledgeGapClassification.Origin.valueOf(row.getClassificationOrigin()),
                row.getClassificationReason(), row.getReviewRevision() == null ? 0 : row.getReviewRevision(),
                row.getReviewedBy(), row.getReviewedAtMs());
        return new KnowledgeGap(row.getQuestionHash(), row.getQuestion(), row.getScopeId(),
            row.getMissCount() == null ? 0 : row.getMissCount(),
            row.getFirstSeenAtMs() == null ? 0 : row.getFirstSeenAtMs(),
            row.getLastSeenAtMs() == null ? 0 : row.getLastSeenAtMs(), evidence, classification);
    }

}
