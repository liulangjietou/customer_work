package com.richard.fyoung.customeradmin.sqlconfig.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 通用查询结果：列名保持 SQL 列序，行数据为列名到值的有序映射；
 * {@code total} 无 count_sql 时为 -1，{@code useMillis} 为本次执行耗时。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SqlQueryResultVO {
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private long total;
    private long useMillis;
}
