package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    /**
     * 内存存储也必须先按租户分区，不能只依赖 scopeId。
     *
     * <p>JDBC 的 {@code tenant_id} 由拦截器强制；内存模式没有 SQL 拦截器，因此在存储层
     * 镜像同一条边界。无上下文的历史直调场景落到 default，保留单租户兼容语义。</p>
     */
    private final Map<String, Map<Long, VersionedEntry>> entriesByTenant = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    @Override
    public void save(SemanticCacheEntry entry) {
        save(entry, BASELINE_GENERATION);
    }

    @Override
    public void save(SemanticCacheEntry entry, String configGeneration) {
        long id = idSequence.incrementAndGet();
        SemanticCacheEntry persisted = new SemanticCacheEntry(id, entry.scopeId(), entry.intent(), entry.question(),
            entry.questionVector(), entry.answer(), entry.hitCount(), entry.createdAtMs(), entry.lastHitAtMs());
        currentEntries().put(id, new VersionedEntry(configGeneration, persisted));
    }

    @Override
    public List<SemanticCacheEntry> findCandidates(String scopeId, String intent, long notBeforeMs, int limit) {
        return findCandidates(scopeId, intent, BASELINE_GENERATION, notBeforeMs, limit);
    }

    @Override
    public List<SemanticCacheEntry> findCandidates(String scopeId, String intent, String configGeneration,
                                                    long notBeforeMs, int limit) {
        List<SemanticCacheEntry> matched = new ArrayList<>();
        for (VersionedEntry versioned : currentEntries().values()) {
            SemanticCacheEntry entry = versioned.entry();
            if (Objects.equals(versioned.configGeneration(), configGeneration)
                && Objects.equals(entry.scopeId(), scopeId)
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
        currentEntries().computeIfPresent(id, (key, versioned) -> {
            SemanticCacheEntry entry = versioned.entry();
            SemanticCacheEntry updated = new SemanticCacheEntry(entry.id(), entry.scopeId(), entry.intent(),
                entry.question(), entry.questionVector(), entry.answer(), entry.hitCount() + 1,
                entry.createdAtMs(), hitAtMs);
            return new VersionedEntry(versioned.configGeneration(), updated);
        });
    }

    @Override
    public long count(String scopeId) {
        return currentEntries().values().stream()
            .map(VersionedEntry::entry)
            .filter(entry -> Objects.equals(entry.scopeId(), scopeId))
            .count();
    }

    @Override
    public long count(String scopeId, String configGeneration) {
        return currentEntries().values().stream()
            .filter(entry -> Objects.equals(entry.configGeneration(), configGeneration))
            .map(VersionedEntry::entry)
            .filter(entry -> Objects.equals(entry.scopeId(), scopeId))
            .count();
    }

    @Override
    public int evictLeastRecentlyUsed(String scopeId, int keepSize) {
        return evictLeastRecentlyUsed(scopeId, null, keepSize);
    }

    @Override
    public int evictLeastRecentlyUsed(String scopeId, String configGeneration, int keepSize) {
        Map<Long, VersionedEntry> entries = currentEntries();
        List<SemanticCacheEntry> owned = new ArrayList<>();
        for (VersionedEntry versioned : entries.values()) {
            SemanticCacheEntry entry = versioned.entry();
            if ((configGeneration == null || Objects.equals(versioned.configGeneration(), configGeneration))
                && Objects.equals(entry.scopeId(), scopeId)) {
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
        for (VersionedEntry versioned : currentEntries().values()) {
            SemanticCacheEntry entry = versioned.entry();
            if (Objects.equals(entry.scopeId(), scopeId)) {
                owned.add(entry);
            }
        }
        owned.sort(MOST_HIT_FIRST);
        return List.copyOf(owned.subList(0, Math.min(Math.max(limit, 0), owned.size())));
    }

    @Override
    public boolean remove(Long id) {
        return currentEntries().remove(id) != null;
    }

    @Override
    public List<SemanticCacheScope> listScopes(int limit) {
        Map<String, Long> counts = new HashMap<>();
        for (VersionedEntry versioned : currentEntries().values()) {
            SemanticCacheEntry entry = versioned.entry();
            counts.merge(entry.scopeId(), 1L, Long::sum);
        }
        List<SemanticCacheScope> scopes = new ArrayList<>(counts.size());
        counts.forEach((scopeId, count) -> scopes.add(new SemanticCacheScope(scopeId, count)));
        // 与 jdbc 实现同序：条目多的在前，同数按分区键字典序，保证两种模式看板顺序一致
        scopes.sort(Comparator.comparingLong(SemanticCacheScope::entries).reversed()
            .thenComparing(SemanticCacheScope::scopeId));
        return List.copyOf(scopes.subList(0, Math.min(Math.max(limit, 0), scopes.size())));
    }

    @Override
    public int clear(String scopeId) {
        Map<Long, VersionedEntry> entries = currentEntries();
        int removed = 0;
        for (VersionedEntry versioned : List.copyOf(entries.values())) {
            SemanticCacheEntry entry = versioned.entry();
            if (Objects.equals(entry.scopeId(), scopeId)) {
                entries.remove(entry.id());
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int clearCurrentTenant() {
        String tenantKey = TenantContext.normalizedTenantKey(TenantContext.require());
        Map<Long, VersionedEntry> removed = entriesByTenant.remove(tenantKey);
        return removed == null ? 0 : removed.size();
    }

    private Map<Long, VersionedEntry> currentEntries() {
        String tenantId = TenantContext.isPresent() ? TenantContext.require() : TenantContext.DEFAULT;
        String tenantKey = TenantContext.normalizedTenantKey(tenantId);
        return entriesByTenant.computeIfAbsent(tenantKey, ignored -> new ConcurrentHashMap<>());
    }

    private record VersionedEntry(String configGeneration, SemanticCacheEntry entry) {
    }
}
