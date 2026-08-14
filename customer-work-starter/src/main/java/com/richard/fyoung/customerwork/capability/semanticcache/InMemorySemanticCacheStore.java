package com.richard.fyoung.customerwork.capability.semanticcache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内语义缓存（默认实现）。
 *
 * <p>多副本部署时各实例各存一份，命中率会被实例数直接除掉；
 * 生产切 {@code semantic-cache.store-mode=jdbc} 让副本共享同一份缓存。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySemanticCacheStore implements SemanticCacheStore {

    /** 最近命中优先：候选集截断时保留最可能再次命中的那批。 */
    private static final Comparator<SemanticCacheEntry> RECENTLY_HIT_FIRST =
        Comparator.comparingLong(SemanticCacheEntry::lastHitAtMs).reversed();

    /** 命中次数优先：运营看的是"哪些缓存真的在被复用"。 */
    private static final Comparator<SemanticCacheEntry> MOST_HIT_FIRST =
        Comparator.comparingLong(SemanticCacheEntry::hitCount).reversed();

    private final Map<Long, SemanticCacheEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    @Override
    public void save(SemanticCacheEntry entry) {
        long id = idSequence.incrementAndGet();
        entries.put(id, new SemanticCacheEntry(id, entry.scopeId(), entry.intent(), entry.question(),
            entry.questionVector(), entry.answer(), entry.hitCount(), entry.createdAtMs(), entry.lastHitAtMs()));
    }

    @Override
    public List<SemanticCacheEntry> findCandidates(String scopeId, String intent, long notBeforeMs, int limit) {
        List<SemanticCacheEntry> matched = new ArrayList<>();
        for (SemanticCacheEntry entry : entries.values()) {
            if (Objects.equals(entry.scopeId(), scopeId)
                && Objects.equals(entry.intent(), intent)
                && entry.createdAtMs() >= notBeforeMs) {
                matched.add(entry);
            }
        }
        matched.sort(RECENTLY_HIT_FIRST);
        return List.copyOf(matched.subList(0, Math.min(Math.max(limit, 0), matched.size())));
    }

    @Override
    public void recordHit(Long id, long hitAtMs) {
        entries.computeIfPresent(id, (key, entry) -> new SemanticCacheEntry(entry.id(), entry.scopeId(),
            entry.intent(), entry.question(), entry.questionVector(), entry.answer(),
            entry.hitCount() + 1, entry.createdAtMs(), hitAtMs));
    }

    @Override
    public long count(String scopeId) {
        return entries.values().stream()
            .filter(entry -> Objects.equals(entry.scopeId(), scopeId))
            .count();
    }

    @Override
    public int evictLeastRecentlyUsed(String scopeId, int keepSize) {
        List<SemanticCacheEntry> owned = new ArrayList<>();
        for (SemanticCacheEntry entry : entries.values()) {
            if (Objects.equals(entry.scopeId(), scopeId)) {
                owned.add(entry);
            }
        }
        if (owned.size() <= keepSize) {
            return 0;
        }
        owned.sort(RECENTLY_HIT_FIRST);
        int removed = 0;
        for (SemanticCacheEntry entry : owned.subList(keepSize, owned.size())) {
            entries.remove(entry.id());
            removed++;
        }
        return removed;
    }

    @Override
    public List<SemanticCacheEntry> listByHits(String scopeId, int limit) {
        List<SemanticCacheEntry> owned = new ArrayList<>();
        for (SemanticCacheEntry entry : entries.values()) {
            if (Objects.equals(entry.scopeId(), scopeId)) {
                owned.add(entry);
            }
        }
        owned.sort(MOST_HIT_FIRST);
        return List.copyOf(owned.subList(0, Math.min(Math.max(limit, 0), owned.size())));
    }

    @Override
    public boolean remove(Long id) {
        return entries.remove(id) != null;
    }

    @Override
    public int clear(String scopeId) {
        int removed = 0;
        for (SemanticCacheEntry entry : List.copyOf(entries.values())) {
            if (Objects.equals(entry.scopeId(), scopeId)) {
                entries.remove(entry.id());
                removed++;
            }
        }
        return removed;
    }
}
