package com.richard.fyoung.customerwork.data.knowledge.vector;

/**
 * 一条向量检索命中。
 *
 * <p>刻意只带标识与分数，<b>不带正文</b>：检索阶段把正文一并拉出来是本项目此前那版实现
 * 最大的开销来源——参与打分的可能是上万条 chunk，最终只有 topN 会被用到，
 * 其余正文全部白读。正文由调用方在拿到 topN 之后按 id 回查。</p>
 *
 * @param chunkId   分片标识
 * @param partition 分片所属的分区（如文档修订 ID），供调用方做归属与 ACL 复核
 * @param score     相似度分数，越大越相近
 * @author owlzhangfq@gmail.com
 */
public record VectorMatch(String chunkId, String partition, double score) {
}
