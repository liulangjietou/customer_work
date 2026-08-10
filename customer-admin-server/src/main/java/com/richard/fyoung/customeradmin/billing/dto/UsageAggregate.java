package com.richard.fyoung.customeradmin.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 用量聚合结果：既承接"从调用日志汇总"，也承接"按区间汇总账单"。
 * @author owlzhangfq@gmail.com
 */
@Data
public class UsageAggregate {

    private String tenantId;
    private LocalDate statDate;
    private String provider;
    private String modelName;
    private Long callCount;
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedTokens;
    private Long totalTokens;
    /** 从调用日志汇总时为空（金额由归集服务按单价算出后回填）。 */
    private BigDecimal amount;
}
