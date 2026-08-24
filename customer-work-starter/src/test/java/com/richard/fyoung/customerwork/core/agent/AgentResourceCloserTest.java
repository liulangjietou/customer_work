package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.tool.ManagedToolkit;
import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentResourceCloserTest {

    @Test
    void closeQuietly_shouldCloseAgentAndOwnedToolkit() {
        ReActAgent agent = mock(ReActAgent.class);
        ManagedToolkit toolkit = mock(ManagedToolkit.class);
        when(agent.getName()).thenReturn("reviewer");
        when(agent.getToolkit()).thenReturn(toolkit);

        AgentResourceCloser.closeQuietly(agent, "cache-eviction");

        verify(agent).close();
        verify(toolkit).close();
    }

    @Test
    void closeQuietly_shouldStillCloseToolkitWhenAgentCloseFails() {
        ReActAgent agent = mock(ReActAgent.class);
        ManagedToolkit toolkit = mock(ManagedToolkit.class);
        when(agent.getName()).thenReturn("reviewer");
        when(agent.getToolkit()).thenReturn(toolkit);
        doThrow(new IllegalStateException("close failed")).when(agent).close();

        AgentResourceCloser.closeQuietly(agent, "cache-eviction");

        verify(agent, times(1)).close();
        verify(toolkit, times(1)).close();
    }
}
