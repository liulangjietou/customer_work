package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型视图对象。{@code apiKeyMasked} 只保留末 4 位，真实密文永不出现在响应体中。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ModelVO {
    private Long id;
    private String modelName;
    private String provider;
    private String apiKeyMasked;
    private String baseUrl;
    private String model;
    private Boolean isDefault;
    private Integer status;
    private Integer testStatus;
    private LocalDateTime testTime;
    private LocalDateTime createTime;
}
