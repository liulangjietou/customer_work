package com.richard.fyoung.customeradmin.sqlconfig.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 配置外部数据源。{@code password} 为 AES/GCM 密文，永不通过接口原样返回
 * （{@code @JsonIgnore} 兜底，回显走 {@code SqlDatasourceVO.passwordMasked}）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sql_datasource")
public class SqlDatasource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String jdbcUrl;
    private String username;
    @JsonIgnore
    private String password;
    /** 0禁用 / 1启用。 */
    private Integer enabled;
    private String remark;

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
