package com.richard.fyoung.customeradmin.sqlconfig.dto;

import lombok.Data;

/**
 * SQL 参数元数据视图对象（管理端配置回显，{@code dropDown} 原样返回 JSON 串）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SqlDefineParamVO {
    private Long id;
    private Long defineId;
    private String paramName;
    private String paramDesc;
    private String paramType;
    private Boolean required;
    private String defaultValue;
    private String dropDown;
    private Boolean isPageNum;
    private Boolean isPageSize;
    private Integer sort;
}
