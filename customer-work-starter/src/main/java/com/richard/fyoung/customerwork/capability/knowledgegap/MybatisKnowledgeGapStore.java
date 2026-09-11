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
        return toDomain(mapper.selectTopGaps(scopeId, limit));
    }

    @Override
    public List<KnowledgeGap> findAll(String scopeId) {
        return toDomain(mapper.selectByScope(scopeId));
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
