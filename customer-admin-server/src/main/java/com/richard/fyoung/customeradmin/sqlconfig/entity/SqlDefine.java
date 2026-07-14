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
 * SQL 查询定义：一条 {@code define_key} 关联一个数据源 + 一段命名参数 SQL，
 * 通用查询接口按 key 执行。{@code query_sql} 强制只读（保存时 SqlValidator 校验）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sql_define")
public class SqlDefine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String defineKey;
    private Long datasourceId;
    private String sqlDescribe;
    private String querySql;
    /** 总数 SQL，可空；为空则前端只有上/下一页无总数。 */
    private String countSql;
    /** 0否 / 1是：打开页面是否自动执行。 */
    private Integer autoLoad;
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
