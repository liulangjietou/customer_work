package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识库视图对象。{@code apiKeyMasked} 只保留末 4 位，真实密文永不出现在响应体中。
 * 字段名为前端契约，不可改动。
 * @author owlzhangfq@gmail.com
 */
@Data
public class KnowledgeBaseVO {
    private Long id;
    private String kbName;
    private String baseUrl;
    private String appId;
    private String apiKeyMasked;
    private String contentType;
    private String extraHeaders;
    private Integer topN;
    private BigDecimal scoreThreshold;
    private Integer status;
    private Integer testStatus;
    private LocalDateTime testTime;
    private String remark;
    private Long currentVersionId;
    private Integer latestVersionNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
