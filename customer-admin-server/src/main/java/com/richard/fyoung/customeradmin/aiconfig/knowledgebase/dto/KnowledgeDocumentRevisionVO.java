package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 文档 lineage 视图；正文不返回，避免列表接口泄露业务文档。 */
@Data
public class KnowledgeDocumentRevisionVO {
    private Long id;
    private Long parentRevisionId;
    private String externalId;
    private String operation;
    private String sourceVersion;
    private String title;
    private String sourceUri;
    private String contentHash;
    private String aclMode;
    private String allowedSubjectTypes;
    private String allowedSubjectIds;
    private String allowedChannels;
    private LocalDateTime sourceUpdatedAt;
    private LocalDateTime createTime;
}
