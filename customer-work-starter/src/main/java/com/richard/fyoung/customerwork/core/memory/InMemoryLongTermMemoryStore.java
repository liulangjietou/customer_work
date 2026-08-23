package com.richard.fyoung.customerwork.core.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内长期记忆存储（{@code customer-work.memory.store-mode=memory} 时装配）。
 *
 * <p>按分区键分区的 {@link ConcurrentHashMap}，开箱即用、离线可测，但<b>重启即清空、多副本各存各的</b>，
 * 生产请用默认的 {@link MybatisLongTermMemoryStore}。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryLongTermMemoryStore implements LongTermMemoryStore {

    /** scopeId -> 事实列表。 */
    private final Map<String, List<String>> scopedFacts = new ConcurrentHashMap<>();

    @Override
    public void add(String scopeId, String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        List<String> facts = scopedFacts.computeIfAbsent(scopeId, k -> new CopyOnWriteArrayList<>());
        String trimmed = fact.trim();
        if (!facts.contains(trimmed)) {
            facts.add(trimmed);
        }
    }

    @Override
    public List<String> recall(String scopeId, String query, int topK) {
        List<String> facts = scopedFacts.get(scopeId);
        if (facts == null) {
            return List.of();
        }
        return FactRelevanceScorer.topMatches(facts, query, topK);
    }

    @Override
    public List<String> list(String scopeId, int limit) {
        List<String> facts = scopedFacts.get(scopeId);
        if (facts == null || limit <= 0) {
            return List.of();
        }
        int from = Math.max(0, facts.size() - limit);
        List<String> recent = new ArrayList<>(facts.subList(from, facts.size()));
        Collections.reverse(recent);
        return List.copyOf(recent);
    }

    @Override
    public void clear(String scopeId) {
        scopedFacts.remove(scopeId);
    }

    @Override
    public void erase(String scopeId) {
        scopedFacts.remove(scopeId);
    }

    @Override
    public int size(String scopeId) {
        List<String> facts = scopedFacts.get(scopeId);
        return facts == null ? 0 : facts.size();
    }
}
