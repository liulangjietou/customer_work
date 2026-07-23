package com.richard.fyoung.customeradmin.sqlconfig.dto;

import lombok.Data;

import java.util.Map;

/**
 * 通用查询页参数元数据（前端据此渲染表单）。{@code defaultValue} 为时间表达式已解析后的实际值，
 * {@code dropDown} 为解析后的 {值:显示名} 映射（无下拉时为 null）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SqlQueryParamMetaVO {
    private String paramName;
    private String paramDesc;
    private String paramType;
    /** 日期格式（仅 DATETIME 类型生效，空表示默认 yyyy-MM-dd HH:mm:ss），驱动前端日期控件粒度与提交格式。 */
    private String dateFormat;
    private Boolean required;
    private String defaultValue;
    private Map<String, String> dropDown;
    private Boolean isPageNum;
    private Boolean isPageSize;
    private Integer sort;
}
