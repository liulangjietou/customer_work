package com.richard.fyoung.customerwork.capability.semanticcache;

/**
 * 语义缓存条目（不可变事实快照）。
 *
 * <p>存的是"某个问题当时是怎么答的"，命中判定靠问题向量的余弦相似度而非字符串相等——
 * "怎么退货"和"退货流程是什么"是同一个问题，按字面比对永远命中不了。</p>
 *
 * <p><b>两层隔离，别混为一谈</b>（与 {@code cw_fact_log} 同一手法）：表上的 {@code tenant_id}
 * 是多租户行级隔离，由 {@code TenantLineInnerInterceptor} 依 {@code TenantContext} 自动改写；
 * 而本记录里的 {@link #scopeId} 是<b>缓存分区键</b>，由 {@code TenantResolver} 从 sessionId 前缀解析。
 * 前者管"哪个租户能看到这行"，后者管"这条缓存属于哪个业务分区"。</p>
 *
 * @param id             条目 ID
 * @param scopeId        缓存分区键（TenantResolver 由 sessionId 解析）
 * @param intent         该问题的意图分类，命中时先按意图缩小候选集
 * @param question       原始问题文本（人读，排查"为什么这条命中了"时要看）
 * @param questionVector 问题向量的存储表示（逗号分隔的浮点数）
 * @param answer         当时的回答
 * @param hitCount       命中次数（容量淘汰时保留高频条目）
 * @param createdAtMs    写入时间戳（毫秒），TTL 以此为准
 * @param lastHitAtMs    最近一次命中时间戳（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record SemanticCacheEntry(
    Long id,
    String scopeId,
    String intent,
    String question,
    String questionVector,
    String answer,
    long hitCount,
    long createdAtMs,
    long lastHitAtMs
) {

    /** 新建条目：尚未命中过。 */
    public static SemanticCacheEntry of(String scopeId, String intent, String question,
                                        String questionVector, String answer, long nowMs) {
        return new SemanticCacheEntry(null, scopeId, intent, question, questionVector, answer,
            0L, nowMs, nowMs);
    }

    /** 是否已过期（相对给定时刻）；{@code ttlMs<=0} 表示不过期。 */
    public boolean isExpired(long nowMs, long ttlMs) {
        return ttlMs > 0 && nowMs - createdAtMs > ttlMs;
    }
}
