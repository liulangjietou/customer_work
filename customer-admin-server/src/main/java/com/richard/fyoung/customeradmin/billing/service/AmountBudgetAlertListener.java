package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.event.UsageAggregationCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 仅在用量归集事务成功提交后检查金额预算。 */
@Slf4j
@Component
public class AmountBudgetAlertListener {

    private static final String MONITOR_ERROR_CODE = "BILLING-AMOUNT-MONITOR-FAIL";

    private final AmountBudgetMonitor monitor;

    public AmountBudgetAlertListener(AmountBudgetMonitor monitor) {
        this.monitor = monitor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterAggregationCommitted(UsageAggregationCompletedEvent event) {
        try {
            monitor.evaluate(event.statDate(), event.tenantIds());
        } catch (Exception e) {
            // 归集已经提交，告警通道失败不能把一次成功归集伪装成失败。
            log.error("amount budget monitor failed, code={}, date={}, tenants={}",
                MONITOR_ERROR_CODE, event.statDate(), event.tenantIds(), e);
        }
    }
}
