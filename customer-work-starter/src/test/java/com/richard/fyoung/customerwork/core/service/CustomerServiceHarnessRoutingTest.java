package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.agent.HarnessAgentFactory;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code CustomerServiceService#resolveAgent} 按 {@code harness.enabled} 在
 * {@link ReActAgent}（轻量主链路）与 {@link HarnessAgent}（长会话压缩保护）之间路由。
 *
 * <p>开关默认已改为开启（客服主链路借此获得 Compaction 长会话保护），本测试同时覆盖
 * 运维显式关闭时的回退路径——那条路径不需要重新发版即可切回轻量 ReActAgent。</p>
 * @author owlzhangfq@gmail.com
 */
class CustomerServiceHarnessRoutingTest {

    private CustomerServiceAgentFactory agentFactory;
    private HarnessAgentFactory harnessAgentFactory;
    private SessionStateManager sessionStateManager;

    @BeforeEach
    void setUp() {
        agentFactory = mock(CustomerServiceAgentFactory.class);
        harnessAgentFactory = mock(HarnessAgentFactory.class);
        sessionStateManager = mock(SessionStateManager.class);
        when(agentFactory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
    }

    private CustomerServiceService buildService(boolean harnessEnabled) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setEnabled(harnessEnabled);
        return new CustomerServiceService(agentFactory, harnessAgentFactory, sessionStateManager, props,
            new MemorySubjectResolver(), empty(), empty(), empty(), empty(), empty(), empty(), empty());
    }

    @Test
    void resolveAgent_shouldUseHarnessAgent_whenHarnessEnabled() {
        ReActAgent inner = mock(ReActAgent.class);
        when(agentFactory.createAgent(anyString())).thenReturn(inner);
        HarnessAgent harnessAgent = mock(HarnessAgent.class);
        when(harnessAgentFactory.createHarnessAgent(anyString())).thenReturn(harnessAgent);
        when(harnessAgent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new TextBlockDeltaEvent("r1", "b1", "你好")));

        CustomerServiceService service = buildService(true);

        StepVerifier.create(service.chatStream("s1", "你好"))
            .expectNext("你好")
            .verifyComplete();

        verify(harnessAgentFactory).createHarnessAgent("s1");
        verify(agentFactory, never()).createAgent(anyString());
    }

    @Test
    void resolveAgent_shouldFallBackToReActAgent_whenHarnessDisabled() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agentFactory.createAgent(anyString())).thenReturn(agent);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new TextBlockDeltaEvent("r1", "b1", "你好")));

        CustomerServiceService service = buildService(false);

        StepVerifier.create(service.chatStream("s2", "你好"))
            .expectNext("你好")
            .verifyComplete();

        verify(agentFactory).createAgent("s2");
        verify(harnessAgentFactory, never()).createHarnessAgent(anyString());
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> empty() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
