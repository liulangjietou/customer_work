package com.richard.fyoung.customeradmin.sqlconfig.dto;

import lombok.Data;

/**
 * 列转换器视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SqlFieldTransformVO {
    private Long id;
    private Long defineId;
    private String fieldName;
    private String transformType;
    private String transformConfig;
}
