package com.richard.fyoung.customerwork.capability.knowledgegap;

/** 只在正式 FAQ 与回执同库提交后存在；保存实际主键和提交时间。 */
public record KnowledgePublicationReceipt(String taskId, String commandFingerprint, long knowledgeId,
                                           long publishedAtMs) {
}
