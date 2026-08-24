package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 同步运行的审计视图。 */
@Data
public class KnowledgeSyncRunVO {
    private Long id;
    private Long sourceId;
    private String requestId;
    private String syncMode;
    private String checkpointBefore;
    private String checkpointAfter;
    private String status;
    private Integer receivedCount;
    private Integer upsertedCount;
    private Integer deletedCount;
    private Integer unchangedCount;
    private Integer activeDocumentCount;
    private Integer duplicateContentCount;
    private BigDecimal qualityScore;
    private String qualityStatus;
    private Long knowledgeBaseVersionId;
    private String snapshotHash;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
