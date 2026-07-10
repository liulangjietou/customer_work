package com.richard.fyoung.customeradmin.system.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限/菜单（树形，parent_id=0 为根节点）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String permName;
    /** 权限标识，如 mcp:add / skill:delete。 */
    private String permCode;
    /** 1菜单 / 2按钮 / 3接口。 */
    private Integer type;
    private String path;
    /** 图标库图标名或上传图片URL，按 iconType 区分。 */
    private String icon;
    /** library=图标库图标名 / image=上传图片URL。 */
    private String iconType;
    private Integer sort;

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
