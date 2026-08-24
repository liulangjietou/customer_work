package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 知识库不可变版本摘要。 */
@Data
public class KnowledgeBaseVersionVO {
    private Long id;
    private Integer versionNo;
    private String checkpoint;
    private String snapshotHash;
    private Integer documentCount;
    private BigDecimal qualityScore;
    private String qualityStatus;
    private String changeNote;
    private LocalDateTime createTime;
}
