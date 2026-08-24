package com.richard.fyoung.customeradmin.workspace.vibecoding.store;

import java.util.Optional;

/** Plan/HITL 挂起态的共享存储 SPI；状态转换必须以 PENDING 为前置条件原子执行。 */
public interface PlanConfirmationStore {

    boolean create(PlanConfirmationRecord record);

    Optional<PlanConfirmationRecord> find(String tenantId, String agentCode, String sessionId, String planId);

    boolean transition(String tenantId, String agentCode, String sessionId, String planId,
                       PlanConfirmationState target);
}
