package com.richard.fyoung.customeradmin.ops.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 候选编辑只接收正文与已看到的修订；租户、状态、评测结果和操作人均不能由浏览器指定。 */
public record KnowledgeCandidateSaveRequest(
    @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String questionHash,
    @Min(0) long expectedRevision,
    @Min(1) long sourceReviewRevision,
    @NotBlank @Size(max = MAX_TITLE_LENGTH) String title,
    @NotBlank @Size(max = MAX_CONTENT_LENGTH) String content,
    @NotBlank @Size(max = MAX_KEYWORD_LENGTH) String keyword
) {
    public static final int MAX_TITLE_LENGTH = 200;
    // Java 与浏览器均按 UTF-16 长度计算；最坏 60,000 UTF-8 字节，始终可放入正式 FAQ 的 TEXT。
    public static final int MAX_CONTENT_LENGTH = 20000;
    public static final int MAX_KEYWORD_LENGTH = 255;
}
