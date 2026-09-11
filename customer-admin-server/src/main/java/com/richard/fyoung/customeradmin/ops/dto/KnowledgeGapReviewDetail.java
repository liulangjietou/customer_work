package com.richard.fyoung.customeradmin.ops.dto;

import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapReview;
import java.util.List;

/** 原始问题、当前分类与一页不可变复核记录。 */
public record KnowledgeGapReviewDetail(KnowledgeGap gap, List<KnowledgeGapReview> history) { }
