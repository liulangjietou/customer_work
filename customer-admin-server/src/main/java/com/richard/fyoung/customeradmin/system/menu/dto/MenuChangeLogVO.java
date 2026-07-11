package com.richard.fyoung.customeradmin.system.menu.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜单变更审计流水展示。
 * @author owlzhangfq@gmail.com
 */
@Data
public class MenuChangeLogVO {
    private Long id;
    private Long menuId;
    private String action;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String operatorName;
    private LocalDateTime createTime;
}
