package com.richard.fyoung.customeradmin.configversion.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置版本返回体。列表场景 {@code content} 为空，详情场景也只返回结构化脱敏快照。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ConfigVersionVO {

    private Long id;
    private String configType;
    private String targetCode;
    private Long targetId;
    private Integer version;
    /** 结构化脱敏快照，仅详情接口返回；密文、请求头和实验分桶盐不会离开服务端。 */
    private String content;
    private String contentHash;
    private String publishScope;
    private String grayTenants;
    private String dataId;
    private String status;
    private Integer sourceVersion;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
}
