package com.richard.fyoung.customeradmin.workspace.knowledge.dto;

/**
 * 代码知识库语义检索命中项（P3-2）：一个分块及其与查询的相似度分。
 * @param sourcePath 来源文件相对路径
 * @param symbol     命中分块对应的符号（类/方法名，可空）
 * @param lang       语言标识
 * @param chunkIndex 同文件内分块序号
 * @param score      余弦相似度（越大越相关）
 * @param snippet    分块内容片段（用于展示与作为问答上下文）
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeSearchHit(String sourcePath, String symbol, String lang, int chunkIndex,
                                 double score, String snippet) {
}
