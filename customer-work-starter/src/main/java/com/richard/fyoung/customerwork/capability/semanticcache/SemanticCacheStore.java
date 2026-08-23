package com.richard.fyoung.customerwork.capability.semanticcache;

import java.util.List;

/**
 * 语义缓存存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemorySemanticCacheStore}；{@code semantic-cache.store-mode=jdbc}
 * 落 {@code cw_semantic_cache} 表，多实例共享同一份缓存——进程内缓存在多副本部署下
 * 命中率会被实例数直接除掉。</p>
 *
 * <p><b>候选集必须有上限</b>：MySQL 8.0 没有原生向量索引，相似度是在应用层逐条算的
 * （与 admin 侧知识检索同一手法）。缓存条目会随时间持续增长，不限候选数的话，
 * 查缓存本身会比调模型还慢——那就本末倒置了。</p>
 *
 * <p>方法签名里的 {@code scopeId} 是缓存分区键，不是租户隔离键；后者由拦截器自动处理，
 * 见 {@link SemanticCacheEntry} 的类注释。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SemanticCacheStore {

    /** 未接入运行时配置前的缓存代际。数据库迁移也以此回填历史条目。 */
    String BASELINE_GENERATION = "bootstrap";

    /** 写入一条缓存。 */
    void save(SemanticCacheEntry entry);

    /**
     * 按配置代际写入。自定义 Store 未实现代际隔离时只能服务基线，收到热配置后必须停止缓存写入。
     */
    default void save(SemanticCacheEntry entry, String configGeneration) {
        if (!BASELINE_GENERATION.equals(configGeneration)) {
            throw new UnsupportedOperationException("semantic cache generation is not supported");
        }
        save(entry);
    }

    /**
     * 取候选集：同分区同意图、未过期的条目，按最近命中时间倒序，最多 {@code limit} 条。
     *
     * <p>按意图先缩一轮是关键剪枝：全表捞出来算余弦，条目一多就退化成线性扫描。</p>
     */
    List<SemanticCacheEntry> findCandidates(String scopeId, String intent, long notBeforeMs, int limit);

    /** 按配置代际取候选；旧自定义 Store 在非基线代际下安全退化为未命中。 */
    default List<SemanticCacheEntry> findCandidates(String scopeId, String intent, String configGeneration,
                                                     long notBeforeMs, int limit) {
        return BASELINE_GENERATION.equals(configGeneration)
            ? findCandidates(scopeId, intent, notBeforeMs, limit) : List.of();
    }

    /** 命中后回写计数与最近命中时间（用于容量淘汰时保留高频条目）。 */
    void recordHit(Long id, long hitAtMs);

    /** 当前分区的条目总数（容量控制用）。 */
    long count(String scopeId);

    /** 当前配置代际的容量计数。 */
    default long count(String scopeId, String configGeneration) {
        return BASELINE_GENERATION.equals(configGeneration) ? count(scopeId) : 0L;
    }

    /** 淘汰：只保留最近命中的 {@code keepSize} 条，其余删除，返回实际删除数。 */
    int evictLeastRecentlyUsed(String scopeId, int keepSize);

    /** 只淘汰当前配置代际，避免旧代际条目挤掉新配置答案。 */
    default int evictLeastRecentlyUsed(String scopeId, String configGeneration, int keepSize) {
        return BASELINE_GENERATION.equals(configGeneration)
            ? evictLeastRecentlyUsed(scopeId, keepSize) : 0;
    }

    /** 清空某分区的缓存（知识库或提示词更新后，旧答案不再可信）。 */
    int clear(String scopeId);

    /**
     * 严格清空当前租户的全部缓存。
     *
     * <p>这是运行时配置切换的一致性边界，与普通查写的“缓存故障可降级”不同：
     * 失败必须抛异常，让上游拒绝切换新配置。实现不得使用 {@code scopeId} 代替租户边界；
     * JDBC 依赖租户拦截器补 {@code tenant_id}，内存实现依赖 {@code TenantContext} 分区。</p>
     *
     * <p>默认失败关闭，保证下游自定义 Store 未显式实现时不会静默遗留旧缓存。</p>
     */
    default int clearCurrentTenant() {
        throw new UnsupportedOperationException("tenant-wide semantic cache invalidation is not supported");
    }

    /**
     * 运营视角列出缓存条目，按命中次数降序。
     *
     * <p>与 {@link #findCandidates} 的区别：那个是命中判定用的、按意图剪枝且按最近命中排序；
     * 这个是给人看的——运营要回答的是"到底缓存了些什么、哪些真的在被复用"，
     * 按命中次数排才能一眼看出缓存有没有价值。</p>
     */
    List<SemanticCacheEntry> listByHits(String scopeId, int limit);

    /** 删除单条缓存（运营发现某条答得不对时定点清除，不必清空整个分区）。 */
    boolean remove(Long id);

    /**
     * 列出当前存在的分区及条目数，按条目数降序，最多 {@code limit} 个。
     *
     * <p><b>必须限额</b>：分区键是用户级隔离键（见 {@link SemanticCacheScope}），
     * 分区数量随活跃用户数增长，无上限地全量返回会在用户一多时把看板拖垮。</p>
     */
    List<SemanticCacheScope> listScopes(int limit);
}
