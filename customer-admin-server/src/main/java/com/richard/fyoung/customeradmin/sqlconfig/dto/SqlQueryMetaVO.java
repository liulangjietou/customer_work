package com.richard.fyoung.customeradmin.sqlconfig.dto;

import lombok.Data;

import java.util.List;

/**
 * 通用查询页元数据：描述 + 是否自动执行 + 是否有总数 SQL + 参数表单元数据。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SqlQueryMetaVO {
    private String defineKey;
    private String sqlDescribe;
    private Boolean autoLoad;
    private Boolean hasCountSql;
    private List<SqlQueryParamMetaVO> params;
}
