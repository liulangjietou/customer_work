package com.richard.fyoung.customerwork.data.knowledge.vector;

import java.util.Collection;
import java.util.List;

/**
 * 一次向量检索请求。
 *
 * @param namespace  命名空间，用于隔离不同用途的向量集合（如知识库 / 语义缓存）
 * @param partitions 限定检索范围的分区集合；<b>空集合表示"没有任何可检索的分区"</b>，
 *                   一律返回空结果而不是全量检索——权限过滤后为空的场景必须落到"查不到"，
 *                   落到"全查"就是越权
 * @param vector     查询向量
 * @param topN       返回条数上限
 * @param threshold  分数下限，低于它的命中直接丢弃
 * @author owlzhangfq@gmail.com
 */
public record VectorQuery(String namespace,
                          Collection<String> partitions,
                          float[] vector,
                          int topN,
                          double threshold) {

    public VectorQuery {
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
        if (topN <= 0) {
            throw new IllegalArgumentException("topN 必须为正数，实际 " + topN);
        }
    }

    /** 是否没有任何可检索的分区。 */
    public boolean hasNoPartition() {
        return partitions.isEmpty();
    }
}
