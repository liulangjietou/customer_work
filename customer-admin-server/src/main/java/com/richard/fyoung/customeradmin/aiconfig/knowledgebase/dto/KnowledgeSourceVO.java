package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 文档源及其最新同步、新鲜度和质量投影。 */
@Data
public class KnowledgeSourceVO {
    private Long id;
    private Long knowledgeBaseId;
    private String sourceCode;
    private String sourceName;
    private String sourceType;
    private Integer status;
    private Integer freshnessSlaMinutes;
    private BigDecimal qualityThreshold;
    private KnowledgeAclRequest defaultAcl;
    private String currentCheckpoint;
    private LocalDateTime lastSyncAt;
    private LocalDateTime lastSuccessfulSyncAt;
    private String lastSyncStatus;
    private String lastSyncError;
    private Integer activeDocumentCount;
    private BigDecimal qualityScore;
    private String qualityStatus;
    private String freshnessStatus;
    private LocalDateTime freshnessDeadline;
    private Integer revision;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
