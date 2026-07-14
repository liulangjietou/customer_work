package com.richard.fyoung.customeradmin.sqlconfig.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源视图对象。{@code passwordMasked} 只保留末 4 位，真实密文永不出现在响应体中。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SqlDatasourceVO {
    private Long id;
    private String name;
    private String jdbcUrl;
    private String username;
    private String passwordMasked;
    private Boolean enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
