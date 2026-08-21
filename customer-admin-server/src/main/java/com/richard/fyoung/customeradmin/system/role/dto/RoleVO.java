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
    /** 数据范围：ALL / TENANT / SELF。 */
    private String dataScope;
    /** 是否为控制面角色；只读返回，创建/编辑接口不接受客户端修改。 */
    private Boolean controlPlane;
    private LocalDateTime createTime;
    private List<Long> permissionIds;
}
