package com.richard.fyoung.customeradmin.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 客服端调用金额事实与 Admin 日账单的逐日逐币种对账结果。 */
public record UsageReconciliationVO(
    String tenantId,
    LocalDate statDate,
    String currency,
    BigDecimal sourceAmount,
    BigDecimal billAmount,
    BigDecimal difference,
    long sourceModelSegments,
    long billModelSegments,
    long sourceSettledSegments,
    long billSettledSegments,
    long sourceUnsettledSegments,
    long billUnsettledSegments,
    long sourceMaxCallLogId,
    long billSourceMaxCallLogId,
    String status,
    String reason
) {
}
