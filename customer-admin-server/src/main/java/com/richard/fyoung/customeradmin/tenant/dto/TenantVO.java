package com.richard.fyoung.customeradmin.tenant.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户列表/详情返回体。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TenantVO {

    private Long id;
    private String tenantCode;
    private String tenantName;
    private String status;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String remark;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;

    /** 是否为保留租户（default / __platform__）：保留租户不允许改编码、不允许冻结或退租。 */
    private Boolean reserved;
}
