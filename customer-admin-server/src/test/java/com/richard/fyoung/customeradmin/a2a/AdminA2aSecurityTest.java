package com.richard.fyoung.customeradmin.a2a;

import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A2A 专用令牌和可信调用主体传播测试。 */
class AdminA2aSecurityTest {

    @AfterEach
    void clearContexts() {
        AgentInvocationIdentityContext.clear();
        TenantContext.clear();
    }

    @Test
    void jsonRpcShouldRejectMissingBearerTokenBeforeProtocolDispatch() {
        AgentScopeA2aServer server = mock(AgentScopeA2aServer.class);
        AdminA2aProperties properties = new AdminA2aProperties();
        properties.setToken("secret-token");
        A2aController controller = new A2aController(server, properties);
        HttpServletRequest request = mock(HttpServletRequest.class);

        Object response = controller.jsonRpc("{}", request);

        assertTrue(response instanceof ResponseEntity<?>);
        assertEquals(401, ((ResponseEntity<?>) response).getStatusCode().value());
        verify(server, never()).getTransportWrapper(any());
    }

    @Test
    void runnerShouldFreezeA2aIdentityAndTenantBeforeBuildingAgent() {
        AgentInstanceCache cache = mock(AgentInstanceCache.class);
        AdminAgentInstanceFactory factory = mock(AdminAgentInstanceFactory.class);
        ReActAgent agent = mock(ReActAgent.class);
        AtomicReference<AgentInvocationIdentity> captured = new AtomicReference<>();
        when(cache.getOrBuild("agent-a")).thenReturn(agent);
        when(factory.contextFor("agent-a", "agent-a")).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            captured.set(AgentInvocationIdentity.capture());
            return RuntimeContext.builder().userId("agent-a").sessionId("agent-a").build();
        });
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.empty());

        new AdminAgentRunner(cache, factory, "agent-a", "test", "tenant-a", "fingerprint")
            .stream(List.of(), null).blockLast();

        AgentInvocationIdentity identity = captured.get();
        assertEquals("tenant-a", identity.tenantId());
        assertEquals(AgentInvocationIdentity.CHANNEL_A2A, identity.channelCode());
        assertEquals("a2a:fingerprint", identity.subjectId());
        assertTrue(identity.authenticated());
        assertNull(AgentInvocationIdentity.capture());
        assertNull(TenantContext.get());
    }
}
