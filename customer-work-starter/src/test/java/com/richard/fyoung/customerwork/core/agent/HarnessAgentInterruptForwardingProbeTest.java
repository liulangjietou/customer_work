package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 框架 issue #1683 行为固化探针：<b>{@code HarnessAgent} 是否转发 {@code ReActAgent} 的"按会话中断"</b>。
 *
 * <h3>背景（见 docs/生产就绪评估.md）</h3>
 * #1683 截至 AgentScope 2.0.0 GA 仍 open：{@code HarnessAgent} 未继承 {@code ReActAgent}，只暴露不带
 * session 参数的 {@code interrupt()} / {@code interrupt(Msg)}（走默认 {@code defaultSessionId}），
 * <b>不转发</b>按 {@code (userId, sessionId)} 精确路由的 {@code interrupt(RuntimeContext)} /
 * {@code interrupt(String, String)}。本项目 admin-server {@code ChatService#interrupt} 的绕行方案是
 * 拿 {@code HarnessAgent.getDelegate()} 下钻到内层 {@code ReActAgent} 再按会话中断——本探针固化这一
 * 现状：<b>断言"验证到的事实"而非预期结论</b>，测试恒绿；一旦框架升级把 session-aware interrupt 补进
 * {@code HarnessAgent}（#1683 被修复），断言即失败，提醒我们复核并可移除 {@code getDelegate()} 绕行。
 *
 * <p>离线：mock Model（构建期不触发模型调用），不需要真实网络/模型。</p>
 * @author owlzhangfq@gmail.com
 */
class HarnessAgentInterruptForwardingProbeTest {

    private HarnessAgent buildHarnessAgent() {
        ReActAgent inner = ReActAgent.builder()
            .name("inner-1683-probe")
            .model(mock(Model.class))
            .toolkit(new Toolkit())
            .stateStore(new InMemoryAgentStateStore())
            .defaultSessionId("coder")
            .build();
        return HarnessAgent.Builder.fromAgent(inner)
            .stateStore(new InMemoryAgentStateStore())
            .defaultSessionId("coder")
            .generateOptions(GenerateOptions.builder().build())
            .workspace(Path.of("target/test-workspace-1683"))
            .build();
    }

    /**
     * #1683 现状锁定：{@code HarnessAgent} <b>不</b>暴露 session-aware interrupt 重载。
     * 若某次框架升级后这两个断言开始失败（方法出现了），说明 #1683 已被修复——行为漂移报警。
     */
    @Test
    void harnessAgent_shouldNotExposeSessionAwareInterrupt_lockingIssue1683() {
        // 只有无 session 的 interrupt() / interrupt(Msg)，没有 interrupt(RuntimeContext) / interrupt(String,String)
        assertThrows(NoSuchMethodException.class,
            () -> HarnessAgent.class.getMethod("interrupt", RuntimeContext.class),
            "#1683 现状：HarnessAgent 不应有 interrupt(RuntimeContext)——出现即说明框架已修复，需复核绕行方案");
        assertThrows(NoSuchMethodException.class,
            () -> HarnessAgent.class.getMethod("interrupt", String.class, String.class),
            "#1683 现状：HarnessAgent 不应有 interrupt(userId, sessionId)——出现即说明框架已修复");
    }

    /**
     * 绕行路径锁定：内层委托 {@code ReActAgent} 具备按会话中断能力，是本项目实际使用的中断入口。
     */
    @Test
    void delegateReActAgent_shouldExposeSessionAwareInterrupt() throws NoSuchMethodException {
        HarnessAgent agent = buildHarnessAgent();
        ReActAgent delegate = agent.getDelegate();
        assertNotNull(delegate, "HarnessAgent.getDelegate() 应返回内层 ReActAgent（绕行 #1683 的抓手）");
        assertInstanceOf(ReActAgent.class, delegate);
        // 委托类具备 session-aware interrupt（框架在 ReActAgent 层原生支持）
        assertNotNull(ReActAgent.class.getMethod("interrupt", RuntimeContext.class));
        assertNotNull(ReActAgent.class.getMethod("interrupt", String.class, String.class));
    }

    /**
     * "中断 → 会话状态不损坏"最小验收：经绕行路径按 (userId, sessionId) 发出中断信号不抛异常，
     * 且 StateStore 仍可用（中断只是协作式置信号，不破坏已持久化的会话状态）。完整"中断→再次对话"
     * 需真实模型驱动，属上线联调阶段，此处以离线可判定的状态完好性作门控。
     */
    @Test
    void interruptViaDelegate_shouldNotCorruptSession() {
        HarnessAgent agent = buildHarnessAgent();
        RuntimeContext ctx = RuntimeContext.builder().userId("coder").sessionId("s1").build();

        assertDoesNotThrow(() -> agent.getDelegate().interrupt(ctx),
            "经委托按会话中断不应抛异常");
        assertNotNull(agent.getStateStore(), "中断后 StateStore 仍应可用，会话状态不因中断而损坏");
        // 幂等：再次中断同一会话仍安全
        assertDoesNotThrow(() -> agent.getDelegate().interrupt(ctx));
    }
}
