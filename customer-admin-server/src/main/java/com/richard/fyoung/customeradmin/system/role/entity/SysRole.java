package com.richard.fyoung.customeradmin.system.role.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 普通租户角色，不具备控制面权限。 */
    public static final int CONTROL_PLANE_DISABLED = 0;
    /** 角色具备控制面权限。该能力由种子/迁移维护，不开放给普通角色编辑接口。 */
    public static final int CONTROL_PLANE_ENABLED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleName;
    /** 唯一，如 super_admin。超管角色特判见 AdminStpInterfaceImpl。 */
    private String roleCode;
    private String remark;
    /** 0禁用 / 1启用。 */
    private Integer status;
    /** 0 普通租户角色 / 1 控制面角色；跨租户授权只认此字段。 */
    private Integer controlPlane;
    /**
     * 数据范围：ALL 当前租户视角内全部 / TENANT 本租户全部 / SELF 仅本人创建。取值见
     * {@link com.richard.fyoung.customeradmin.datascope.DataScope}，为空按 SELF 处理。
     */
    private String dataScope;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
