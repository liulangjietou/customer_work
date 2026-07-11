package com.richard.fyoung.customeradmin.system.permission.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限/菜单树节点。
 * @author owlzhangfq@gmail.com
 */
@Data
public class PermissionVO {
    private Long id;
    private Long parentId;
    private String permName;
    private String permCode;
    private Integer type;
    private String path;
    private String icon;
    private String iconType;
    private Integer sort;
    private List<PermissionVO> children = new ArrayList<>();
}
