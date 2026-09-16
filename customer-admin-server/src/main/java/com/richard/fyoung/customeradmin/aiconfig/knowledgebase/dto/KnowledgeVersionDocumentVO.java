package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgePreviewStatus;
import java.time.LocalDateTime;

/** 版本内文档的授权元数据；列表不携带正文或 ACL 主体清单。 */
public record KnowledgeVersionDocumentVO(
    Long knowledgeBaseId, Long versionId, Integer versionNo, Long revisionId,
    KnowledgePreviewStatus status, String title, String externalId, String sourceName,
    String sourceVersion, String sourceUri, String contentHash, boolean currentRevision,
    LocalDateTime sourceUpdatedAt, LocalDateTime revisionCreatedAt) {
}
