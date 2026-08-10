package com.richard.fyoung.customeradmin.billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 租户配额新增/编辑请求（按 tenantId + period 覆盖）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TenantQuotaSaveRequest {

    @NotBlank(message = "租户不能为空")
    private String tenantId;

    /** DAILY / MONTHLY。 */
    @NotBlank(message = "周期不能为空")
    private String period;

    /** token 上限，0 = 不限。 */
    @Min(value = 0, message = "token 上限不能为负")
    private Long tokenLimit;

    /** 金额上限（元），0 = 不限；实时链路只拦 token，金额走 T+1 账单告警。 */
    private BigDecimal amountLimit;

    /** BLOCK / DEGRADE / WARN。 */
    private String exceedAction;

    @Min(value = 0, message = "预警阈值不能为负")
    @Max(value = 100, message = "预警阈值不能超过 100")
    private Integer warnPercent;

    private Boolean enabled;
}
