package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 一个外部文档的 UPSERT/DELETE 变更。 */
public record KnowledgeDocumentChangeRequest(
    @NotBlank(message = "operation 不能为空") String operation,
    @NotBlank(message = "externalId 不能为空") @Size(max = 512, message = "externalId 不能超过 512 字符")
    String externalId,
    @Size(max = 255, message = "sourceVersion 不能超过 255 字符") String sourceVersion,
    @Size(max = 512, message = "title 不能超过 512 字符") String title,
    @Size(max = 2048, message = "sourceUri 不能超过 2048 字符") String sourceUri,
    String content,
    LocalDateTime sourceUpdatedAt,
    KnowledgeAclRequest acl) {
}
