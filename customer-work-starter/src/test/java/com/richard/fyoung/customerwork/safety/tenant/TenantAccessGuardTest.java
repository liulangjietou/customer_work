package com.richard.fyoung.customerwork.safety.tenant;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TenantAccessGuardTest {

    @Test
    void disabledFeatureAndDefaultTenant_shouldPreserveCompatibility() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        TenantAccessNacosService nacos = mock(TenantAccessNacosService.class);
        TenantAccessGuard guard = new TenantAccessGuard(
            properties, new TenantAccessSnapshotStore(), nacos);

        assertEquals(TenantAccessDecision.Kind.ALLOWED,
            guard.check("acme", null, true).kind());
        properties.getNacos().setTenantAccessEnabled(true);
        assertEquals(TenantAccessDecision.Kind.ALLOWED,
            guard.check("DEFAULT", null, true).kind());
    }

    @Test
    void missingSnapshot_shouldTrackTenantAndFailClosed() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getNacos().setTenantAccessEnabled(true);
        TenantAccessNacosService nacos = mock(TenantAccessNacosService.class);
        TenantAccessGuard guard = new TenantAccessGuard(
            properties, new TenantAccessSnapshotStore(), nacos);

        assertEquals(TenantAccessDecision.Kind.SNAPSHOT_UNAVAILABLE,
            guard.check("acme", null, false).kind());
        verify(nacos).track("acme");
    }
}
