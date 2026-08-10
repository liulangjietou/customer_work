package com.richard.fyoung.customeradmin.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 租户配额返回体。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TenantQuotaVO {

    private String tenantId;
    private String period;
    private Long tokenLimit;
    private BigDecimal amountLimit;
    private String exceedAction;
    private Integer warnPercent;
    private Boolean enabled;
}
