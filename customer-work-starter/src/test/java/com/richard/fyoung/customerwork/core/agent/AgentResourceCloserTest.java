package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.tool.ManagedToolkit;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentResourceCloserTest {

    /**
     * 冲突计量按进程内唯一实例工作，不建就直接 no-op。
     *
     * <p>**不建它的话，下面两条「计量炸了也要照常释放」的断言全是恒真的**——
     * {@code recordQuietly} 第一行就因为拿不到实例返回了，压根走不到会抛的那一步。
     * 第一版就是那样写的，变异测试（把 unwrap 挪回 try 外）没打红才发现。</p>
     */
    private AgentStateConflictMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new AgentStateConflictMetrics(new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        metrics.unregister();
    }

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

    /**
     * 冲突计量是旁路，炸了也不能挡住释放。
     *
     * <p>本批次给 {@code closeQuietly} 加了一句 {@code AgentStateConflictMetrics.recordQuietly}，
     * 而它排在原有 try 块<b>之前</b>——漏出去一个异常，关 Agent 与关 Toolkit 就都不会执行，
     * 为了一个指标丢掉资源释放。这条断言钉住「计量抛了，释放照做」。</p>
     */
    @Test
    void closeQuietly_shouldStillReleaseWhenConflictMetricsThrows() {
        ReActAgent agent = mock(ReActAgent.class);
        ManagedToolkit toolkit = mock(ManagedToolkit.class);
        when(agent.getName()).thenReturn("reviewer");
        when(agent.getToolkit()).thenReturn(toolkit);
        // 计量读的就是这个值；让它抛，模拟框架侧任何一步出问题
        when(agent.getStateConflictCount()).thenThrow(new IllegalStateException("metrics boom"));

        AgentResourceCloser.closeQuietly(agent, "cache-eviction");

        verify(agent, times(1)).close();
        verify(toolkit, times(1)).close();
    }

    /**
     * 同上，但炸在<b>取内层 Agent</b> 这一步。
     *
     * <p>两条分开写是因为它们落在计量方法的不同位置：上一条炸在读计数值时，
     * 而这条炸在 {@code HarnessAgent#getDelegate()}——第一版实现把这一步放在了 try 之外，
     * 上一条断言照不出来。守边界的测试要覆盖边界的每一侧。</p>
     */
    @Test
    void closeQuietly_shouldStillReleaseWhenUnwrappingHarnessAgentThrows() {
        HarnessAgent agent = mock(HarnessAgent.class);
        ManagedToolkit toolkit = mock(ManagedToolkit.class);
        when(agent.getName()).thenReturn("harness-reviewer");
        when(agent.getToolkit()).thenReturn(toolkit);
        when(agent.getDelegate()).thenThrow(new IllegalStateException("unwrap boom"));

        AgentResourceCloser.closeQuietly(agent, "cache-eviction");

        verify(agent, times(1)).close();
        verify(toolkit, times(1)).close();
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
