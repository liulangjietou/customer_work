package com.richard.fyoung.customeradmin.governance.change;

import com.richard.fyoung.customeradmin.governance.change.mapper.GovernedChangeRequestMapper;
import com.richard.fyoung.customeradmin.governance.change.service.GovernedChangeStateService;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 将超时待审批请求推进到确定终态，并同步追加审计。 */
@Slf4j
@Component
public class GovernedChangeExpiryScheduler {

    private static final int BATCH_SIZE = 100;

    private final GovernedChangeRequestMapper mapper;
    private final GovernedChangeStateService stateService;
    private final GovernanceProperties properties;

    public GovernedChangeExpiryScheduler(GovernedChangeRequestMapper mapper,
                                         GovernedChangeStateService stateService,
                                         GovernanceProperties properties) {
        this.mapper = mapper;
        this.stateService = stateService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${admin.governance.expiry-scan-interval-ms:60000}")
    public void expirePending() {
        CrossTenantOperations.execute(() -> mapper.selectExpiredAcrossTenants(
                LocalDateTime.now(), BATCH_SIZE))
            .forEach(request -> {
                try {
                    TenantContext.runWith(request.getTenantId(),
                        () -> stateService.expire(request.getId(), request.getTenantId()));
                } catch (Exception e) {
                    log.error("governed change expiry failed, code={}, requestId={}",
                        "GOVERNED-CHANGE-EXPIRY-FAIL", request.getId(), e);
                }
            });
        LocalDateTime executionCutoff = LocalDateTime.now()
            .minusSeconds(properties.effectiveExecutionTimeoutSeconds());
        CrossTenantOperations.execute(() -> mapper.selectStaleExecutingAcrossTenants(
                executionCutoff, BATCH_SIZE))
            .forEach(request -> {
                try {
                    TenantContext.runWith(request.getTenantId(), () -> stateService.failTimedOutExecution(
                        request.getId(), request.getTenantId(), executionCutoff));
                } catch (Exception e) {
                    log.error("governed change execution recovery failed, code={}, requestId={}",
                        "GOVERNED-CHANGE-RECOVERY-FAIL", request.getId(), e);
                }
            });
    }
}
