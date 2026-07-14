package com.richard.fyoung.customeradmin.sqlconfig.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 查询参数元数据：对应 SQL 里的 {@code :paramName}，驱动前端表单渲染与执行时的参数绑定。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sql_define_param")
public class SqlDefineParam {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long defineId;
    private String paramName;
    private String paramDesc;
    /** STRING / INTEGER / DATETIME。 */
    private String paramType;
    /** 0否 / 1是。 */
    private Integer required;
    /** 默认值，支持 ${now}/${now-14d}/${now-2h} 时间表达式。 */
    private String defaultValue;
    /** 下拉选项（JSON 对象 {"值":"显示名"}，可空）。 */
    private String dropDown;
    /** 0否 / 1是：分页页码参数，其值换算为 offset 绑定。 */
    private Integer isPageNum;
    /** 0否 / 1是：分页页大小参数，绑定 pageSize 本身。 */
    private Integer isPageSize;
    /** 表单排序（升序）。 */
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
