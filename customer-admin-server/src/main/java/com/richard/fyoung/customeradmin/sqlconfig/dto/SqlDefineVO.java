package com.richard.fyoung.customeradmin.sqlconfig.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 定义视图对象。{@code datasourceName} 冗余带出，便于列表直接展示。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SqlDefineVO {
    private Long id;
    private String defineKey;
    private Long datasourceId;
    private String datasourceName;
    private String sqlDescribe;
    private String querySql;
    private String countSql;
    private Boolean autoLoad;
    private Boolean enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
