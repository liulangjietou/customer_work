package com.richard.fyoung.customerwork.capability.knowledgegap;

/** 已确认的发布前提冲突；数据库不可达等未知结果不使用此异常，必须继续核对回执。 */
public class KnowledgePublicationConflictException extends RuntimeException {
    public KnowledgePublicationConflictException(String message) {
        super(message);
    }
}
