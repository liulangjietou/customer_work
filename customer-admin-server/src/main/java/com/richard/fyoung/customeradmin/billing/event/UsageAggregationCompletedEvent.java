package com.richard.fyoung.customeradmin.billing.event;

import java.time.LocalDate;
import java.util.Set;

/** 用量归集事务内发布的领域事件；监听方只在事务提交后执行预算检查。 */
public record UsageAggregationCompletedEvent(LocalDate statDate, Set<String> tenantIds) {

    public UsageAggregationCompletedEvent {
        tenantIds = tenantIds == null ? Set.of() : Set.copyOf(tenantIds);
    }
}
