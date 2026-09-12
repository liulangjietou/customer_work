package com.richard.fyoung.customerwork.data.chatlog;

import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.dto.TaskPlanItem;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import java.util.List;

/** 随助手正文同次保存的答复信息；来源引用与展示线索分开，不保存追踪或模型内部上下文。 */
public record ChatAnswerEvidence(String finishReason, List<KnowledgeCitation> citations,
                                 List<TaskPlanItem> taskPlan, List<KnowledgeRetrievalSource> retrievalSources) {

    public ChatAnswerEvidence {
        citations = citations == null ? List.of() : List.copyOf(citations);
        taskPlan = taskPlan == null ? List.of() : List.copyOf(taskPlan);
        retrievalSources = retrievalSources == null ? List.of() : List.copyOf(retrievalSources);
    }
}
