package com.richard.fyoung.customerwork.core.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态冲突计量。
 *
 * <h3>这个指标为什么重要</h3>
 * <p>升级到 2.0.3 之后写状态默认走乐观并发，而默认的 {@code ConflictPolicy.OVERWRITE}
 * 会把 CAS 失败悄悄吸收掉——重读最新版本再覆盖，不抛异常、不打日志。也就是说
 * <b>会话状态互相覆盖这件事发生了，链路上没有任何痕迹</b>。本指标是它唯一的出口，
 * 同时也是 {@code SessionLock} 在 Redis 故障时保护性降级进程内之后，
 * 能看出"串行锁其实已经不管用了"的唯一间接证据。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class AgentStateConflictMetricsTest {

    private static final String METER = "customerwork.agent.state.conflicts";

    private AgentStateConflictMetrics metrics;
    private SimpleMeterRegistry registry;

    @AfterEach
    void tearDown() {
        if (metrics != null) {
            metrics.unregister();
        }
    }

    private void given() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentStateConflictMetrics(registry);
    }

    @Test
    @DisplayName("冲突数按 owner 归类累加")
    void conflictsAreCountedByOwner() {
        given();

        metrics.record(3, "agui:conv-8f3a");
        metrics.record(2, "agui:conv-99ff");
        metrics.record(1, "multi-agent-router");

        assertEquals(5.0, counter("agui").count(),
            "同一条链路的冲突应累加到同一个时间序列上");
        assertEquals(1.0, counter("multi-agent-router").count());
    }

    /**
     * 标签基数是监控系统的硬约束。
     *
     * <p>调用方传的 owner 形如 {@code "customer-session-discard:u12:conv-8f3a"}，
     * 原样当标签会让时间序列随会话数无限膨胀。冒号后的部分是实例标识，
     * 对"哪条链路在冲突"这个问题没有信息量。</p>
     */
    @Test
    @DisplayName("owner 归一化为低基数标签")
    void ownerIsNormalizedToLowCardinality() {
        assertEquals("agui", AgentStateConflictMetrics.normalizeOwner("agui:conv-8f3a"));
        assertEquals("customer-session-discard",
            AgentStateConflictMetrics.normalizeOwner("customer-session-discard:u12:conv-1"));
        assertEquals("multi-agent-reducer",
            AgentStateConflictMetrics.normalizeOwner("multi-agent-reducer"));
        assertEquals(AgentStateConflictMetrics.OWNER_UNKNOWN,
            AgentStateConflictMetrics.normalizeOwner(null));
        assertEquals(AgentStateConflictMetrics.OWNER_UNKNOWN,
            AgentStateConflictMetrics.normalizeOwner("  "));
        assertEquals(AgentStateConflictMetrics.OWNER_UNKNOWN,
            AgentStateConflictMetrics.normalizeOwner(":conv-1"));
    }

    @Test
    @DisplayName("零冲突不写点：正常路径应当在注册表里完全不出现")
    void zeroConflictsWritesNothing() {
        given();

        metrics.record(0, "agui:conv-1");

        assertNull(registry.find(METER).counter(),
            "绝大多数会话冲突数为 0，为它们各建一个时间序列只是给注册表添噪声");
    }

    @Test
    @DisplayName("没有 MeterRegistry 时退化为空操作，不得影响 Agent 释放")
    void degradesWithoutRegistry() {
        metrics = new AgentStateConflictMetrics((io.micrometer.core.instrument.MeterRegistry) null);

        metrics.record(3, "agui:conv-1");
    }

    /**
     * 非 ReActAgent（如 HarnessAgent）静默跳过。
     *
     * <p>{@code getStateConflictCount()} 是 {@link io.agentscope.core.ReActAgent} 独有的，
     * 释放入口收到的却是 {@code Agent} 接口。可观测是旁路，取不到就跳过，
     * 不能因此影响释放流程本身。</p>
     */
    @Test
    @DisplayName("非 ReActAgent 与 null 都静默跳过")
    void nonReActAgentIsSkipped() {
        given();

        AgentStateConflictMetrics.recordQuietly(null, "agui:conv-1");

        assertNull(registry.find(METER).counter());
    }

    /**
     * 计量必须真的接在唯一的释放入口上。
     *
     * <p>这条与上面几条正交：上面验的是"这段逻辑对不对"，这里验的是"它有没有被接到实际路径上"。
     * 本仓库的缺陷形状恰恰是后者——能力造好了，另一条路径没接，而两边都不报错。
     * {@code getStateConflictCount()} 在离线测试里恒为 0，因此无法用行为断言覆盖这一点，
     * 只能对结构本身下断言，手法与 {@code AgentAssemblyAlignmentTest} 一致。</p>
     */
    @Test
    @DisplayName("释放入口 AgentResourceCloser 必须调用计量")
    void closerInvokesMetrics() throws java.io.IOException {
        java.nio.file.Path closer = java.nio.file.Paths.get("src/main/java/com/richard/fyoung/"
            + "customerwork/core/agent/AgentResourceCloser.java");
        if (!java.nio.file.Files.exists(closer)) {
            closer = java.nio.file.Paths.get("customer-work-starter").resolve(closer);
        }
        String source = java.nio.file.Files.readString(closer, java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(source.contains("AgentStateConflictMetrics.recordQuietly"),
            "AgentResourceCloser 没有采集状态冲突数——11 处 Agent 释放全走它，"
                + "漏了这一行指标就永远是空的，而不会有任何报错");
    }

    private Counter counter(String owner) {
        Counter counter = registry.find(METER)
            .tag(AgentStateConflictMetrics.TAG_OWNER, owner).counter();
        if (counter == null) {
            throw new AssertionError("没有找到 owner=" + owner + " 的计数器；已注册的 meter："
                + registry.getMeters().stream().map(m -> m.getId().toString()).toList());
        }
        return counter;
    }
}
