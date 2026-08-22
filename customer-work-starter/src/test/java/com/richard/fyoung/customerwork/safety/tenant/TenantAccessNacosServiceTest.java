package com.richard.fyoung.customerwork.safety.tenant;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAccessNacosServiceTest {

    @Test
    void refreshTenant_shouldReuseConfiguredNacosAndDedicatedDataId() throws Exception {
        CustomerWorkProperties properties = properties();
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig("customer-work-tenant-access-tenant-acme", "ACCESS_GROUP", 3000L))
            .thenReturn(json("acme", "ACTIVE", 5L));
        TenantAccessSnapshotStore store = new TenantAccessSnapshotStore();
        TenantAccessNacosService service = new TenantAccessNacosService(
            properties, store, ignored -> configService);

        assertTrue(service.refreshTenant("acme"));

        verify(configService).addListener(
            org.mockito.ArgumentMatchers.eq("customer-work-tenant-access-tenant-acme"),
            org.mockito.ArgumentMatchers.eq("ACCESS_GROUP"), any());
        assertEquals(5L, store.current("acme").getAccessEpoch());
    }

    @Test
    void applyConfig_shouldRejectOlderEpoch() {
        CustomerWorkProperties properties = properties();
        TenantAccessSnapshotStore store = new TenantAccessSnapshotStore();
        TenantAccessNacosService service = new TenantAccessNacosService(
            properties, store, ignored -> mock(ConfigService.class));

        assertTrue(service.applyConfig("acme", json("acme", "SUSPENDED", 8L)));
        assertFalse(service.applyConfig("acme", json("acme", "ACTIVE", 7L)));
        assertEquals("SUSPENDED", store.current("acme").getStatus());
    }

    @Test
    void restrictiveSnapshotShouldDisconnectExistingTenantWebSockets() {
        CustomerWorkProperties properties = properties();
        TenantAccessSnapshotStore store = new TenantAccessSnapshotStore();
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        TenantAccessNacosService service = new TenantAccessNacosService(
            properties, store, ignored -> mock(ConfigService.class), registry);

        assertTrue(service.applyConfig("acme", json("acme", "SUSPENDED", 8L)));

        verify(registry).disconnectTenant("acme");
    }

    @Test
    void activeSnapshotShouldAllowTenantWebSocketRegistration() {
        CustomerWorkProperties properties = properties();
        TenantAccessSnapshotStore store = new TenantAccessSnapshotStore();
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        TenantAccessNacosService service = new TenantAccessNacosService(
            properties, store, ignored -> mock(ConfigService.class), registry);

        assertTrue(service.applyConfig("acme", json("acme", "ACTIVE", 9L)));

        verify(registry).allowTenant("acme");
        verify(registry, never()).disconnectTenant("acme");
    }

    @Test
    void activeAccessEpochAdvanceShouldDisconnectOldSessionsAndAllowNewEpochReconnect() {
        CustomerWorkProperties properties = properties();
        TenantAccessSnapshotStore store = new TenantAccessSnapshotStore();
        WsSessionRegistry registry = new WsSessionRegistry(new ObjectMapper());
        TenantAccessNacosService service = new TenantAccessNacosService(
            properties, store, ignored -> mock(ConfigService.class), registry);
        assertTrue(service.applyConfig("acme", json("acme", "ACTIVE", 8L)));
        Sinks.Many<String> oldSession = TenantContext.callWith(
            "acme", () -> registry.registerUser("user-1"));

        assertTrue(service.applyConfig("acme", json("acme", "ACTIVE", 9L)));

        StepVerifier.create(oldSession.asFlux()).verifyComplete();
        assertFalse(registry.isTenantRestricted("acme"));
        assertEquals(0, registry.onlineUsers());
        TenantContext.runWith("acme", () -> registry.registerUser("user-1"));
        assertEquals(1, registry.onlineUsers());
    }

    @Test
    void staleSnapshotShouldDisconnectTenantDuringCompensatingRefresh() throws Exception {
        CustomerWorkProperties properties = properties();
        properties.getNacos().setTenantCode("acme");
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig("customer-work-tenant-access-tenant-acme", "ACCESS_GROUP", 3000L))
            .thenReturn(null);
        TenantAccessSnapshotStore store = mock(TenantAccessSnapshotStore.class);
        when(store.evaluate(org.mockito.ArgumentMatchers.eq("acme"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(30_000L)))
            .thenReturn(new TenantAccessDecision(TenantAccessDecision.Kind.SNAPSHOT_STALE, 3L));
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        TenantAccessNacosService service = new TenantAccessNacosService(
            properties, store, ignored -> configService, registry);

        service.refreshAllSafely();

        verify(registry).disconnectTenant("acme");
    }

    private CustomerWorkProperties properties() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getNacos().setTenantAccessEnabled(true);
        properties.getNacos().setGroup("ACCESS_GROUP");
        return properties;
    }

    private String json(String tenantId, String status, long epoch) {
        return "{\"schemaVersion\":1,\"tenantId\":\"" + tenantId
            + "\",\"status\":\"" + status + "\",\"accessEpoch\":" + epoch
            + ",\"expireTime\":null,\"changedAtMs\":1}";
    }
}
