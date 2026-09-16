package com.richard.fyoung.customerwork.data.rag.search;

/** 托管检索返回的真实版本成员标识，外部文本标记不参与构建。 */
public record KnowledgeDocumentReference(Long knowledgeBaseId, Long versionId,
                                          Long revisionId, Long chunkId) {

    /** 缺失旧数据标识时只能展示来源线索，不能发起原文预览。 */
    public boolean complete() {
        return positive(knowledgeBaseId) && positive(versionId) && positive(revisionId) && positive(chunkId);
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
