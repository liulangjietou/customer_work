package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 不可变路由版本视图。 */
@Data
public class ModelRouteVersionVO {
    private Long id;
    private Integer versionNo;
    private String status;
    private String contentHash;
    private String changeNote;
    private Long activatedBy;
    private LocalDateTime activatedAt;
    private Long createBy;
    private LocalDateTime createTime;
    private List<ModelRouteRuleVO> rules;
}
