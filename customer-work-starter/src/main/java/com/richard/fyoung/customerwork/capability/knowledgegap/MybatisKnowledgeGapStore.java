package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.capability.knowledgegap.entity.KnowledgeGapDO;
import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-Plus 知识盲区存储（{@code knowledge-gap.store-mode=jdbc} 时装配）。
 *
 * <p>全部操作失败只记日志：盲区统计挂在每次知识检索的尾巴上，它出问题最坏是少一条排行数据，
 * 绝不该让用户的问题因此答不出来。</p>
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
            mapper.upsertMiss(row);
        } catch (Exception e) {
            log.error("[MybatisKnowledgeGapStore] recordMiss failed, errorCode={}, scopeId={}",
                "KNOWLEDGE-GAP-SAVE-FAIL", scopeId, e);
        }
    }

    @Override
    public List<KnowledgeGap> topGaps(String scopeId, int limit) {
        try {
            return toDomain(mapper.selectTopGaps(scopeId, limit));
        } catch (Exception e) {
            log.error("[MybatisKnowledgeGapStore] topGaps failed, errorCode={}, scopeId={}",
                "KNOWLEDGE-GAP-TOP-FAIL", scopeId, e);
            return List.of();
        }
    }

    @Override
    public List<KnowledgeGap> findAll(String scopeId) {
        try {
            return toDomain(mapper.selectByScope(scopeId));
        } catch (Exception e) {
            log.error("[MybatisKnowledgeGapStore] findAll failed, errorCode={}, scopeId={}",
                "KNOWLEDGE-GAP-FIND-FAIL", scopeId, e);
            return List.of();
        }
    }

    private List<KnowledgeGap> toDomain(List<KnowledgeGapDO> rows) {
        List<KnowledgeGap> result = new ArrayList<>(rows.size());
        for (KnowledgeGapDO row : rows) {
            result.add(new KnowledgeGap(
                row.getQuestionHash(),
                row.getQuestion(),
                row.getScopeId(),
                row.getMissCount() == null ? 0L : row.getMissCount(),
                row.getFirstSeenAtMs() == null ? 0L : row.getFirstSeenAtMs(),
                row.getLastSeenAtMs() == null ? 0L : row.getLastSeenAtMs()));
        }
        return result;
    }
}
