package com.richard.fyoung.customerworkapp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.richard.fyoung.customerwork.data.knowledge.KnowledgePublicSource;

/** 消息内一个已保存引用的原文响应，正文作为普通字符串传输。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserMessageSourcePreview(int sourceIndex, UserMessageSource.Status status, String title,
                                       String knowledgeBase, Integer versionNo, String sourceVersion, String content) {

    /** 元数据与正文来自同一次授权查询；失效时一起清空。 */
    public static UserMessageSourcePreview from(int index, KnowledgePublicSource source) {
        UserMessageSource metadata = UserMessageSource.from(index, source);
        return new UserMessageSourcePreview(index, metadata.status(), metadata.title(), metadata.knowledgeBase(),
            metadata.versionNo(), metadata.sourceVersion(), source == null ? null : source.content());
    }
}
