package com.richard.fyoung.customeradmin.system.role.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class RoleVO {
    private Long id;
    private String roleName;
    private String roleCode;
    private String remark;
    private Integer status;
    private LocalDateTime createTime;
    private List<Long> permissionIds;
}
