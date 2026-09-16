package com.richard.fyoung.customerwork.capability.knowledgegap;

/** 一次人工复核的不可变记录，保留保存时的计数快照与改动前后分类。 */
public record KnowledgeGapReview(long revision, KnowledgeGapCategory previousCategory,
                                 KnowledgeGapPriority previousPriority, KnowledgeGapCategory category,
                                 KnowledgeGapPriority priority, String reason, String reviewedBy,
                                 long reviewedAtMs, long signalCount) { }
