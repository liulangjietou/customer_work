package com.richard.fyoung.customerwork.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

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
}
