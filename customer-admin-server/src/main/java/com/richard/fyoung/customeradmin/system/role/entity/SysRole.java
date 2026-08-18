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

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleName;
    /** 唯一，如 super_admin。超管角色特判见 AdminStpInterfaceImpl。 */
    private String roleCode;
    private String remark;
    /** 0禁用 / 1启用。 */
    private Integer status;
    /**
     * 数据范围：ALL 全部租户 / TENANT 本租户全部 / SELF 仅本人创建。取值见
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
