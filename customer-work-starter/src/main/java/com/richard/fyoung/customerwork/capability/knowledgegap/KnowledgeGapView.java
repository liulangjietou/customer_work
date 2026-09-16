package com.richard.fyoung.customerwork.capability.knowledgegap;

/** 工作清单把明确的问候、实时问题分流，但人工高优先级始终保留。 */
public enum KnowledgeGapView {
    WORK, ALL, PENDING, KNOWLEDGE, DEPENDENCY, PROCESS, REALTIME, NON_BUSINESS;

    /** 内存实现与 JDBC 使用同一筛选语义。 */
    public boolean includes(KnowledgeGap gap) {
        var classification = gap.classification();
        if (this == ALL) return true;
        if (this == WORK) {
            return classification.priority() == KnowledgeGapPriority.HIGH
                || (classification.category() != KnowledgeGapCategory.NON_BUSINESS
                    && classification.category() != KnowledgeGapCategory.REALTIME);
        }
        return name().equals(classification.category().name());
    }
}
