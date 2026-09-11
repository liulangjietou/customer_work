package com.richard.fyoung.customerwork.data.rag.search;

import java.util.List;

/** 多库检索的返回快照；部分库失败或尚未完成时，保留成功召回并明确完整性。 */
public record KnowledgeSearchResult(List<KnowledgeNode> nodes, boolean complete) {

    public KnowledgeSearchResult {
        nodes = List.copyOf(nodes);
    }
}
