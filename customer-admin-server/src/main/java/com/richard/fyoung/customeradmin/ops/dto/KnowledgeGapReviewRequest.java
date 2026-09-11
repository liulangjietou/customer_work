package com.richard.fyoung.customeradmin.ops.dto;

import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapCategory;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapPriority;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 入口完成格式校验；用户身份和源分区均不接受请求正文填报。 */
public record KnowledgeGapReviewRequest(
    @Min(0) @Max(9007199254740990L) long expectedRevision,
    @NotNull KnowledgeGapCategory category,
    @NotNull KnowledgeGapPriority priority,
    @NotBlank @Size(max = 1000) String reason) { }
