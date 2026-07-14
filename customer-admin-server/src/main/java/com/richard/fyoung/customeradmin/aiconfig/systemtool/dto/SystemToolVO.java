package com.richard.fyoung.customeradmin.aiconfig.systemtool.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统工具视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SystemToolVO {
    private Long id;
    private String toolCode;
    private String toolName;
    private String description;
    /** 0禁用 / 1启用。 */
    private Integer enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
