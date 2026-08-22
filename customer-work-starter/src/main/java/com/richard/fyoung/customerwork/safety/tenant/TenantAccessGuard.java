package com.richard.fyoung.customerwork.safety.tenant;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.springframework.stereotype.Component;

/** API Key、JWT、坐席令牌与 WS 共用的运行时租户访问门禁。 */
@Component
public class TenantAccessGuard {

    private final CustomerWorkProperties properties;
    private final TenantAccessSnapshotStore snapshotStore;
    private final TenantAccessNacosService nacosService;

    public TenantAccessGuard(CustomerWorkProperties properties,
                             TenantAccessSnapshotStore snapshotStore,
                             TenantAccessNacosService nacosService) {
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.nacosService = nacosService;
    }

    /** 纯内存判定；请求热路径不访问 Nacos。 */
    public TenantAccessDecision check(String tenantId, Long expectedEpoch, boolean requireEpoch) {
        if (!properties.getNacos().isTenantAccessEnabled() || TenantContext.isDefaultTenant(tenantId)) {
            return TenantAccessDecision.allowed(0L);
        }
        if (!TenantContext.isValidTenantId(tenantId)) {
            return TenantAccessDecision.unavailable();
        }
        nacosService.track(tenantId);
        return snapshotStore.evaluate(tenantId, expectedEpoch, requireEpoch,
            System.currentTimeMillis(), properties.getNacos().getTenantAccessMaxStalenessMs());
    }

    /** 缺快照或快照过期时同步回读一次；调用方必须位于阻塞线程池。 */
    public TenantAccessDecision refreshAndCheck(String tenantId, Long expectedEpoch, boolean requireEpoch) {
        TenantAccessDecision decision = check(tenantId, expectedEpoch, requireEpoch);
        if (decision.kind() == TenantAccessDecision.Kind.SNAPSHOT_UNAVAILABLE
            || decision.kind() == TenantAccessDecision.Kind.SNAPSHOT_STALE) {
            nacosService.refreshTenant(tenantId);
            return check(tenantId, expectedEpoch, requireEpoch);
        }
        return decision;
    }

    public boolean isEnabled() {
        return properties.getNacos().isTenantAccessEnabled();
    }
}
