package com.richard.fyoung.customerwork.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS 连接登记处单测：注册/推送、离线返回 false、顶号完成旧流、广播。
 * @author owlzhangfq@gmail.com
 */
class WsSessionRegistryTest {

    private WsSessionRegistry registry() {
        return new WsSessionRegistry(new ObjectMapper());
    }

    @Test
    void pushToUser_shouldDeliverFrameToRegisteredSink() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> sink = registry.registerUser("U1");
        StepVerifier.create(sink.asFlux())
            .then(() -> assertTrue(registry.pushToUser("U1", WsFrame.system("hi"))))
            .assertNext(json -> {
                assertTrue(json.contains("\"type\":\"system\""));
                assertTrue(json.contains("hi"));
            })
            .thenCancel()
            .verify();
    }

    @Test
    void pushToUser_offline_shouldReturnFalse() {
        assertFalse(registry().pushToUser("ghost", WsFrame.system("x")));
    }

    @Test
    void registerUser_secondConnection_shouldSupersedeAndCompleteOldSink() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> old = registry.registerUser("U1");
        // 顶号：再次注册应完成旧流
        StepVerifier.create(old.asFlux())
            .then(() -> registry.registerUser("U1"))
            .verifyComplete();
        assertEquals(1, registry.onlineUsers(), "顶号后同一用户仍只有一条在线连接");
    }

    @Test
    void broadcastToAgents_shouldReachAllAgents() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> a1 = registry.registerAgent("agent-1");
        Sinks.Many<String> a2 = registry.registerAgent("agent-2");
        registry.broadcastToAgents(WsFrame.ticketNew("payload"));

        StepVerifier.create(a1.asFlux())
            .assertNext(json -> assertTrue(json.contains("\"type\":\"ticket_new\"")))
            .thenCancel().verify();
        StepVerifier.create(a2.asFlux())
            .assertNext(json -> assertTrue(json.contains("\"type\":\"ticket_new\"")))
            .thenCancel().verify();
    }

    @Test
    void unregister_shouldRemoveSinkAndDecrementCount() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> sink = registry.registerAgent("agent-1");
        assertEquals(1, registry.onlineAgents());
        registry.unregisterAgent("agent-1", sink);
        assertEquals(0, registry.onlineAgents());
        assertFalse(registry.pushToAgent("agent-1", WsFrame.system("x")));
    }

    @Test
    void sameUserIdInDifferentTenants_shouldUseIndependentSinks() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> tenantA = TenantContext.callWith(
            "tenant-a", () -> registry.registerUser("U1"));
        Sinks.Many<String> tenantB = TenantContext.callWith(
            "tenant-b", () -> registry.registerUser("U1"));

        TenantContext.runWith("tenant-a", () ->
            assertTrue(registry.pushToUser("U1", WsFrame.system("only-a"))));
        TenantContext.runWith("tenant-b", () ->
            assertTrue(registry.pushToUser("U1", WsFrame.system("only-b"))));

        StepVerifier.create(tenantA.asFlux())
            .assertNext(json -> assertTrue(json.contains("only-a")))
            .thenCancel().verify();
        StepVerifier.create(tenantB.asFlux())
            .assertNext(json -> assertTrue(json.contains("only-b")))
            .thenCancel().verify();
        assertEquals(2, registry.onlineUsers());
    }

    @Test
    void tenantCaseAliases_shouldShareOneInternalSlot() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> sink = TenantContext.callWith(
            "Acme", () -> registry.registerUser("U1"));

        TenantContext.runWith("acme", () ->
            assertTrue(registry.pushToUser("U1", WsFrame.system("same-tenant"))));

        StepVerifier.create(sink.asFlux())
            .assertNext(json -> assertTrue(json.contains("same-tenant")))
            .thenCancel().verify();
        assertEquals(1, registry.onlineUsers());
    }

    @Test
    void disconnectTenantShouldCompleteOnlyTargetTenantConnections() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> userA = TenantContext.callWith(
            "tenant-a", () -> registry.registerUser("U1"));
        Sinks.Many<String> agentA = TenantContext.callWith(
            "tenant-a", () -> registry.registerAgent("A1"));
        TenantContext.callWith("tenant-b", () -> registry.registerUser("U1"));

        StepVerifier.create(userA.asFlux())
            .then(() -> registry.disconnectTenant("tenant-a"))
            .verifyComplete();
        StepVerifier.create(agentA.asFlux()).verifyComplete();

        assertEquals(1, registry.onlineUsers());
        assertEquals(0, registry.onlineAgents());
        TenantContext.runWith("tenant-b", () ->
            assertTrue(registry.pushToUser("U1", WsFrame.system("still-connected"))));
    }

    @Test
    void restrictedTenantShouldRejectRegistrationUntilActiveSnapshotAllowsIt() {
        WsSessionRegistry registry = registry();
        registry.disconnectTenant("tenant-a");

        Sinks.Many<String> rejected = TenantContext.callWith(
            "tenant-a", () -> registry.registerUser("U1"));
        StepVerifier.create(rejected.asFlux()).verifyComplete();
        assertEquals(0, registry.onlineUsers());
        assertTrue(registry.isTenantRestricted("tenant-a"));

        registry.allowTenant("TENANT-A");
        TenantContext.callWith("tenant-a", () -> registry.registerUser("U1"));
        assertEquals(1, registry.onlineUsers());
        assertFalse(registry.isTenantRestricted("tenant-a"));
    }

    @Test
    void epochChangeShouldDisconnectExistingSessionsWithoutPermanentlyRestrictingTenant() {
        WsSessionRegistry registry = registry();
        Sinks.Many<String> oldSession = TenantContext.callWith(
            "tenant-a", () -> registry.registerUser("U1"));

        registry.disconnectTenantSessionsForEpochChange("tenant-a");

        StepVerifier.create(oldSession.asFlux()).verifyComplete();
        assertFalse(registry.isTenantRestricted("tenant-a"));
        TenantContext.runWith("tenant-a", () -> registry.registerUser("U1"));
        assertEquals(1, registry.onlineUsers());
    }

    @Test
    void concurrentUserRegistrationAndDisconnectShouldNeverLeaveEscapedSession() throws Exception {
        assertConcurrentRegistrationClosed(true);
    }

    @Test
    void concurrentAgentRegistrationAndDisconnectShouldNeverLeaveEscapedSession() throws Exception {
        assertConcurrentRegistrationClosed(false);
    }

    private void assertConcurrentRegistrationClosed(boolean user) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 100; round++) {
                WsSessionRegistry registry = registry();
                CyclicBarrier barrier = new CyclicBarrier(2);
                String connectionId = (user ? "U" : "A") + round;
                Future<Sinks.Many<String>> registration = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return TenantContext.callWith("tenant-a", () -> user
                        ? registry.registerUser(connectionId)
                        : registry.registerAgent(connectionId));
                });
                Future<?> disconnect = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    registry.disconnectTenant("tenant-a");
                    return null;
                });

                Sinks.Many<String> sink = registration.get(5, TimeUnit.SECONDS);
                disconnect.get(5, TimeUnit.SECONDS);

                assertTrue(registry.isTenantRestricted("tenant-a"));
                assertEquals(0, user ? registry.onlineUsers() : registry.onlineAgents());
                StepVerifier.create(sink.asFlux())
                    .expectComplete()
                    .verify(Duration.ofSeconds(1));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
