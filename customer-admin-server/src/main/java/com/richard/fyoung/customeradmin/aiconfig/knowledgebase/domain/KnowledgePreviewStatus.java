package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain;

/** 原文的当前可读状态；不可读时不返回标题、来源位置或正文。 */
public enum KnowledgePreviewStatus {
    AVAILABLE,
    FORBIDDEN,
    UNAVAILABLE
}
