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
 * SQL 结果列转换器：对匹配 {@code field_name} 的结果列做展示层转换
 * （DATE_FORMAT 格式化时间 / VALUE_MAP 值映射）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sql_field_transform")
public class SqlFieldTransform {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long defineId;
    private String fieldName;
    /** DATE_FORMAT / VALUE_MAP。 */
    private String transformType;
    /** DATE_FORMAT 为格式串（如 MM-dd HH:mm:ss）；VALUE_MAP 为 JSON 映射 {"原值":"显示值"}。 */
    private String transformConfig;

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
