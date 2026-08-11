package com.richard.fyoung.customerwork.safety.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 租户配额持久化对象（贫血 DO，对应 {@code cw_tenant_quota}）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_tenant_quota")
public class TenantQuotaDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 归属租户。
     *
     * <p>本表在租户拦截器的忽略清单外（正常参与过滤），但仍显式持有该字段：
     * 运营方跨租户配额度时要读到"这条配额属于谁"，靠拦截器自动补值读不出来。</p>
     */
    private String tenantId;
    private String period;
    private Long tokenLimit;
    private BigDecimal amountLimit;
    private String exceedAction;
    private Integer warnPercent;
    private Integer enabled;
    private String remark;
    private Long createdAtMs;
    private Long updatedAtMs;
}
