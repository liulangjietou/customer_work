package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customerwork.core.runtime.SandboxSafeAgentStateStore;
import io.agentscope.core.state.AgentStateStore;

/**
 * {@link AgentStateStore} 装饰器的 admin 侧薄壳，实现在 starter 的
 * {@link SandboxSafeAgentStateStore}（两侧唯一实现）。
 *
 * <p>装饰目的：规避 {@code agentscope-harness}（2.0.0 GA）与 {@code agentscope-extensions-mysql}
 * （2.0.0 GA）组合使用时的官方框架 bug——{@code HarnessAgent} 的 {@code SessionSandboxStateStore}
 * 内部固定给沙箱状态槽位拼出 {@code agentId + "sandbox/agent/"}
 * （{@link io.agentscope.harness.agent.IsolationScope#AGENT} 场景，其余三种 IsolationScope 也都
 * 硬编码了 {@code /} 前缀，反解字节码逐一确认过），而 {@code MysqlAgentStateStore#validateSessionId}
 * 明确拒绝任何包含 {@code /}/{@code \} 的 sessionId，两者一组合必然抛
 * {@code IllegalArgumentException}，导致 Docker/沙箱模式下每次对话都直接失败。</p>
 *
 * <p>只在 {@code sessionId} 里替换掉路径分隔符再转发给底层 store，其余参数原样透传；真实业务
 * sessionId（VibeCoding 用的 UUID）本身不含 {@code /}，转义是幂等的，不影响正常对话状态的存取。
 * 只在 {@code admin.sandbox.mode=docker} 时才套这层装饰（见
 * {@link AdminAgentInstanceFactory#build}），local 模式不受影响。</p>
 * @author owlzhangfq@gmail.com
 */
public class AdminSandboxSafeAgentStateStore extends SandboxSafeAgentStateStore {

    public AdminSandboxSafeAgentStateStore(AgentStateStore delegate) {
        super(delegate);
    }
}
