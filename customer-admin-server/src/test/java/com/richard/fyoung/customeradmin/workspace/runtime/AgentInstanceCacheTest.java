package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customerwork.tool.ManagedToolkit;
import io.agentscope.core.ReActAgent;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
        when(factory.build(org.mockito.ArgumentMatchers.eq("agent-a"), any(AgentMemoryScope.class)))
            .thenAnswer(invocation -> {
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
    void evict_shouldCloseAgentAndToolkitBeforeDroppingOwnership() {
        ReActAgent agent = mock(ReActAgent.class);
        ManagedToolkit toolkit = mock(ManagedToolkit.class);
        when(agent.getName()).thenReturn("agent-a");
        when(agent.getToolkit()).thenReturn(toolkit);
        when(factory.build(org.mockito.ArgumentMatchers.eq("agent-a"), any(AgentMemoryScope.class)))
            .thenReturn(agent);

        cache.getOrBuild("agent-a");
        cache.evict("agent-a");

        verify(agent).close();
        verify(toolkit).close();
    }

    @Test
    void evictAll_shouldForceRebuild_forEveryListedAgentCode() {
        when(factory.build(org.mockito.ArgumentMatchers.eq("agent-b"), any(AgentMemoryScope.class)))
            .thenReturn(mock(Agent.class));
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

    @Test
    void sharedRevisionChange_shouldRebuildWithoutLocalEviction() {
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        AiAgent revisionOne = runtimeAgent(1L);
        AiAgent revisionTwo = runtimeAgent(2L);
        when(agentMapper.selectOne(any())).thenReturn(revisionOne, revisionTwo);
        when(factory.build(org.mockito.ArgumentMatchers.eq("agent-a"), any(AgentMemoryScope.class)))
            .thenAnswer(invocation -> mock(Agent.class));
        AgentInstanceCache revisionAwareCache = new AgentInstanceCache(factory, agentMapper);

        Agent first = revisionAwareCache.getOrBuild("agent-a");
        Agent second = revisionAwareCache.getOrBuild("agent-a");

        assertNotSame(first, second);
        verify(factory, times(2)).build(org.mockito.ArgumentMatchers.eq("agent-a"), any(AgentMemoryScope.class));
    }

    private AiAgent runtimeAgent(long revision) {
        AiAgent agent = new AiAgent();
        agent.setId(1L);
        agent.setAgentCode("agent-a");
        agent.setStatus(1);
        agent.setRuntimeRevision(revision);
        return agent;
    }
}
