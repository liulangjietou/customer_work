package com.richard.fyoung.customerwork.capability.knowledgegap;

/** 其他运营人员已提交新的分类，调用方应读回并保留自己的输入。 */
public class KnowledgeGapReviewConflictException extends RuntimeException {
    public KnowledgeGapReviewConflictException() {
        super("Knowledge gap review revision changed");
    }
}
