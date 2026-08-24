package com.richard.fyoung.customeradmin.workspace.vibecoding.store;

import com.richard.fyoung.customeradmin.workspace.vibecoding.store.entity.AiPlanConfirmation;
import com.richard.fyoung.customeradmin.workspace.vibecoding.store.mapper.AiPlanConfirmationMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** MySQL 权威实现；唯一键和 PENDING 条件更新共同保证跨 Pod 只有一个终态胜出。 */
@Repository
public class JdbcPlanConfirmationStore implements PlanConfirmationStore {

    private final AiPlanConfirmationMapper mapper;

    public JdbcPlanConfirmationStore(AiPlanConfirmationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean create(PlanConfirmationRecord record) {
        AiPlanConfirmation row = new AiPlanConfirmation();
        row.setTenantId(record.tenantId());
        row.setAgentCode(record.agentCode());
        row.setSessionId(record.sessionId());
        row.setPlanId(record.planId());
        row.setStatus(record.state().name());
        row.setExpireAt(record.expireAt());
        return mapper.insert(row) == 1;
    }

    @Override
    public Optional<PlanConfirmationRecord> find(String tenantId, String agentCode, String sessionId, String planId) {
        AiPlanConfirmation row = mapper.find(tenantId, agentCode, sessionId, planId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new PlanConfirmationRecord(row.getTenantId(), row.getAgentCode(), row.getSessionId(),
            row.getPlanId(), PlanConfirmationState.valueOf(row.getStatus()), row.getExpireAt()));
    }

    @Override
    public boolean transition(String tenantId, String agentCode, String sessionId, String planId,
                              PlanConfirmationState target) {
        return mapper.transition(tenantId, agentCode, sessionId, planId, target.name()) == 1;
    }
}
