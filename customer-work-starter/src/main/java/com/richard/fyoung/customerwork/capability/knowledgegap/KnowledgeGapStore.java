package com.richard.fyoung.customerwork.capability.knowledgegap;

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

    /** 未命中次数排行（降序），即"最该优先补的知识"。 */
    List<KnowledgeGap> topGaps(String scopeId, int limit);

    /** 按问题哈希查一条。 */
    List<KnowledgeGap> findAll(String scopeId);
}
