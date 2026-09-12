package com.richard.fyoung.customerwork.data.rag.search;

import java.math.BigDecimal;

/** 一条实际送入模型上下文的检索来源；保留序号和定位信息，不复制文档正文。 */
public record KnowledgeRetrievalSource(int number, String knowledgeBaseName, String documentId,
                                        String chunkId, BigDecimal score,
                                        KnowledgeDocumentReference documentReference) {

    public KnowledgeRetrievalSource {
        // JSON 存储可能去除末尾零；分数相同不能因小数位数不同而误判快照不一致。
        score = score == null ? null : score.stripTrailingZeros();
    }

    /** 序号由最终排序与截断后的列表决定，不能从模型输出或文档正文解析。 */
    public static KnowledgeRetrievalSource from(int number, KnowledgeNode node) {
        return new KnowledgeRetrievalSource(number, node.kbName(), node.docId(), node.chunkId(),
            node.score(), node.documentReference());
    }
}
