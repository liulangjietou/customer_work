package com.richard.fyoung.customeradmin.workspace.chat.dto;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeVersionDocumentVO;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalResult;
import java.math.BigDecimal;
import java.util.List;

/** 本轮实际检索的参考资料；不声称模型逐条引用，也不把未记录解释为无命中。 */
public record ChatKnowledgeSourcesVO(Status status, List<Retrieval> retrievals) {

    public enum Status { RECORDED, NOT_RECORDED }

    public enum SourceStatus { AVAILABLE, FORBIDDEN, UNAVAILABLE, EXTERNAL }

    public record Retrieval(String agentCode, KnowledgeRetrievalResult.Status status, List<Source> sources) {
    }

    /** sourceId 只在本消息的不可变记录中定位，不接受浏览器提供的知识库或修订 ID。 */
    public record Source(int sourceId, int number, SourceStatus status, String knowledgeBaseName,
                         String documentId, String chunkId, BigDecimal score,
                         KnowledgeVersionDocumentVO document) {
    }
}
