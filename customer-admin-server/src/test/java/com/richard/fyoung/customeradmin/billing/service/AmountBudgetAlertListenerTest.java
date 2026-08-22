package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.event.UsageAggregationCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AmountBudgetAlertListenerTest {

    @Test
    void listener_shouldRunOnlyAfterAggregationTransactionCommit() throws Exception {
        Method method = AmountBudgetAlertListener.class.getMethod(
            "afterAggregationCommitted", UsageAggregationCompletedEvent.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }

    @Test
    void listener_shouldDelegateCommittedTenantSetToMonitor() {
        AmountBudgetMonitor monitor = mock(AmountBudgetMonitor.class);
        AmountBudgetAlertListener listener = new AmountBudgetAlertListener(monitor);
        UsageAggregationCompletedEvent event = new UsageAggregationCompletedEvent(
            LocalDate.of(2026, 8, 10), Set.of("tenant-a", "tenant-b"));

        listener.afterAggregationCommitted(event);

        verify(monitor).evaluate(event.statDate(), event.tenantIds());
    }
}
