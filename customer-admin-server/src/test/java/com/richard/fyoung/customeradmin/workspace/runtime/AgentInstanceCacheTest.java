package com.richard.fyoung.customeradmin.workspace.runtime;

import io.agentscope.core.agent.Agent;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentInstanceCache} 单测：惰性重建 + 命中不重建 + evict 后下次调用重新构建。
 * @author owlzhangfq@gmail.com
 */
class AgentInstanceCacheTest {

    private AdminAgentInstanceFactory factory;
    private AgentInstanceCache cache;
    private AtomicInteger buildCount;

    @BeforeEach
    void setUp() {
        factory = mock(AdminAgentInstanceFactory.class);
        cache = new AgentInstanceCache(factory);
        buildCount = new AtomicInteger();
        when(factory.build("agent-a")).thenAnswer(invocation -> {
            buildCount.incrementAndGet();
            return mock(Agent.class);
        });
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void getOrBuild_shouldOnlyBuildOnce_forRepeatedCalls() {
        Agent first = cache.getOrBuild("agent-a");
        Agent second = cache.getOrBuild("agent-a");

        assertSame(first, second);
        assertEquals(1, buildCount.get());
    }

    @Test
    void evict_shouldForceRebuild_onNextCall() {
        Agent first = cache.getOrBuild("agent-a");
        cache.evict("agent-a");
        Agent second = cache.getOrBuild("agent-a");

        assertNotSame(first, second);
        assertEquals(2, buildCount.get());
    }

    @Test
    void evictAll_shouldForceRebuild_forEveryListedAgentCode() {
        when(factory.build("agent-b")).thenReturn(mock(Agent.class));
        Agent a1 = cache.getOrBuild("agent-a");
        Agent b1 = cache.getOrBuild("agent-b");

        cache.evictAll(List.of("agent-a", "agent-b"));

        Agent a2 = cache.getOrBuild("agent-a");
        assertNotSame(a1, a2);
        assertEquals(2, buildCount.get());
    }

    @Test
    void evict_unknownAgentCode_shouldBeNoOp() {
        cache.evict("never-built");
        // no exception
    }

    @Test
    void getOrBuild_shouldIsolateSameAgentCode_betweenTenants() {
        TenantContext.set("tenant-a");
        Agent tenantA = cache.getOrBuild("agent-a");
        TenantContext.set("tenant-b");
        Agent tenantB = cache.getOrBuild("agent-a");
        TenantContext.set("tenant-a");

        assertNotSame(tenantA, tenantB);
        assertSame(tenantA, cache.getOrBuild("agent-a"));
        assertEquals(2, buildCount.get());
    }
}
