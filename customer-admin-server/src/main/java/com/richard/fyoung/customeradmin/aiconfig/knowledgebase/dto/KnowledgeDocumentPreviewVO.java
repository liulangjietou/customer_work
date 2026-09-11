package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

/** 经过本次请求授权的不可变文档原文，不替换成当前修订的正文。 */
public record KnowledgeDocumentPreviewVO(KnowledgeVersionDocumentVO document, String content) {
}
