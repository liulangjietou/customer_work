package com.richard.fyoung.customeradmin.workspace.project.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目展示。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ProjectVO {
    private Long id;
    private String projectName;
    private String description;
    /** 已收进本项目的会话数（跨智能体合计）。 */
    private Integer sessionCount;
    private LocalDateTime createTime;
}
