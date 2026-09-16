package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.data.rag.search.KnowledgeGapEvidence;
import java.util.List;

/**
 * 知识盲区存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryKnowledgeGapStore}；{@code knowledge-gap.store-mode=jdbc}
 * 落 {@code cw_knowledge_gap} 表。</p>
 *
 * <p>{@link #recordMiss} 是"存在即累加、不存在则新建"的 upsert：盲区表是计数表而非流水表，
 * 未命中的绝对量在客服场景很大，逐条落库既贵又淹没重点。</p>
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeGapStore {

    /** 记一次未命中：同问题累加计数并刷新最近出现时间。 */
    void recordMiss(String question, String scopeId, long nowMs);

    /** 携带最近一次来源；旧自定义存储可继续仅记录计数。 */
    default void recordMiss(String question, String scopeId, long nowMs, KnowledgeGapEvidence evidence) {
        recordMiss(question, scopeId, nowMs);
    }

    /** 原始未命中次数排行（降序），保留没有分类筛选的兼容读取。 */
    List<KnowledgeGap> topGaps(String scopeId, int limit);

    /** 先筛选再截取排行，避免高频问候占满工作清单；JDBC 实现在 SQL 内完成。 */
    default List<KnowledgeGap> topGaps(String scopeId, int limit, KnowledgeGapView view) {
        return findAll(scopeId).stream().filter(view::includes)
            .sorted(java.util.Comparator
                .comparing((KnowledgeGap gap) -> gap.classification().priority() == KnowledgeGapPriority.HIGH)
                .thenComparingLong(KnowledgeGap::missCount).thenComparingLong(KnowledgeGap::lastSeenAtMs)
                .reversed())
            .limit(Math.max(limit, 0)).toList();
    }

    /** 查询某分区的全部未命中记录；读取失败应由调用方处理。 */
    List<KnowledgeGap> findAll(String scopeId);
}
