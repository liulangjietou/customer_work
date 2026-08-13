package com.richard.fyoung.customerwork.capability.knowledgegap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内知识盲区存储（默认实现）。
 *
 * <p>重启即清空计数；生产切 {@code knowledge-gap.store-mode=jdbc}——
 * 盲区排行要攒够一段时间才有说服力，重启清零等于永远看不出"反复"。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryKnowledgeGapStore implements KnowledgeGapStore {

    /** 未命中次数降序，同次数按最近出现时间降序：一样常问的，先补最近还在问的。 */
    private static final Comparator<KnowledgeGap> MOST_MISSED_FIRST =
        Comparator.comparingLong(KnowledgeGap::missCount)
            .thenComparingLong(KnowledgeGap::lastSeenAtMs)
            .reversed();

    private final Map<String, KnowledgeGap> gaps = new ConcurrentHashMap<>();

    @Override
    public void recordMiss(String question, String scopeId, long nowMs) {
        String normalized = KnowledgeGap.normalize(question);
        if (normalized.isEmpty()) {
            return;
        }
        String key = scopeId + '#' + KnowledgeGap.hashOf(normalized);
        gaps.compute(key, (k, existing) -> existing == null
            ? KnowledgeGap.firstMiss(normalized, scopeId, nowMs)
            : existing.hitAgain(nowMs));
    }

    @Override
    public List<KnowledgeGap> topGaps(String scopeId, int limit) {
        List<KnowledgeGap> matched = findAll(scopeId);
        matched.sort(MOST_MISSED_FIRST);
        return List.copyOf(matched.subList(0, Math.min(Math.max(limit, 0), matched.size())));
    }

    @Override
    public List<KnowledgeGap> findAll(String scopeId) {
        List<KnowledgeGap> matched = new ArrayList<>();
        for (KnowledgeGap gap : gaps.values()) {
            if (Objects.equals(gap.scopeId(), scopeId)) {
                matched.add(gap);
            }
        }
        return matched;
    }
}
