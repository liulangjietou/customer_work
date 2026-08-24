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
    private Long modelSegmentCount;
    private Long settledSegmentCount;
    private Long unsettledSegmentCount;
    /** COMPLETE/PARTIAL/UNAVAILABLE，由分段结算状态聚合得出。 */
    private String pricingStatus;
    /** 本行归集冻结的客服端调用日志最大 ID。 */
    private Long sourceMaxCallLogId;
    private String currency;
    /** 已 SETTLED 分段的不可变金额之和。 */
    private BigDecimal amount;
}
