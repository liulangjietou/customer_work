package com.richard.fyoung.customerworkapp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.richard.fyoung.customerwork.data.knowledge.KnowledgePublicSource;

/** 客户消息的原文目录；不可读取时不返回历史留存的标题等元数据。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserMessageSource(int sourceIndex, Status status, String title, String knowledgeBase,
                                Integer versionNo, String sourceVersion) {
    public enum Status { AVAILABLE, UNAVAILABLE }

    /** 只使用本次授权 SQL 返回的元数据，禁止用旧来源线索补齐失效资料。 */
    public static UserMessageSource from(int index, KnowledgePublicSource source) {
        return source == null ? new UserMessageSource(index, Status.UNAVAILABLE, null, null, null, null)
            : new UserMessageSource(index, Status.AVAILABLE, source.title(), source.knowledgeBase(),
                source.versionNo(), source.sourceVersion());
    }
}
