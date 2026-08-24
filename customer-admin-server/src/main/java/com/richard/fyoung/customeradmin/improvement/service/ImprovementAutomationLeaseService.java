package com.richard.fyoung.customeradmin.improvement.service;

import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customeradmin.improvement.mapper.AgentImprovementCaseMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 多 Admin 副本以数据库 CAS 租约竞争发布跟踪与效果观察工作。 */
@Service
public class ImprovementAutomationLeaseService {

    private static final long MIN_LEASE_MS = 1000L;

    private final AgentImprovementCaseMapper mapper;
    private final ImprovementAutomationProperties properties;
    private final String workerId;

    public ImprovementAutomationLeaseService(AgentImprovementCaseMapper mapper,
                                              ImprovementAutomationProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();
    }

    public List<AgentImprovementCase> claimDue() {
        long now = System.currentTimeMillis();
        int limit = Math.max(1, Math.min(properties.getBatchSize(), 200));
        long leaseMs = Math.max(MIN_LEASE_MS, properties.getLeaseMs());
        List<AgentImprovementCase> candidates = CrossTenantOperations.execute(
            () -> mapper.findDueCandidates(now, limit));
        List<AgentImprovementCase> claimed = new ArrayList<>();
        for (AgentImprovementCase candidate : candidates) {
            int changed = CrossTenantOperations.execute(() -> mapper.claim(
                candidate.getId(), workerId, now, now + leaseMs));
            if (changed == 1) {
                candidate.setLeaseOwner(workerId);
                candidate.setLeaseUntilMs(now + leaseMs);
                claimed.add(candidate);
            }
        }
        return claimed;
    }
}
