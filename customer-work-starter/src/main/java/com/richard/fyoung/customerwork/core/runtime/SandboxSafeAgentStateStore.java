package com.richard.fyoung.customerwork.core.runtime;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link AgentStateStore} 装饰器：规避 {@code agentscope-harness}（2.0.0 GA）与
 * {@code agentscope-extensions-mysql}（2.0.0 GA）组合使用时的官方框架 bug——
 * {@code HarnessAgent} 的 {@code SessionSandboxStateStore} 内部固定给沙箱状态槽位拼出
 * {@code agentId + "sandbox/agent/"}（{@link io.agentscope.harness.agent.IsolationScope#AGENT}
 * 场景，其余三种 IsolationScope 也都硬编码了 {@code /} 前缀，反解字节码逐一确认过），
 * 而 {@code MysqlAgentStateStore#validateSessionId} 明确拒绝任何包含 {@code /}/{@code \}
 * 的 sessionId，两者一组合必然抛 {@code IllegalArgumentException}，导致 Docker/沙箱模式下
 * 每次对话都直接失败。
 *
 * <p>只在 {@code sessionId} 里替换掉路径分隔符再转发给底层 store，其余参数原样透传；真实业务
 * sessionId（VibeCoding 用的 UUID）本身不含 {@code /}，转义是幂等的，不影响正常对话状态的存取。
 * 由调用方决定何时套这层装饰（如仅在 docker 沙箱模式下），starter 不注册为 Bean。</p>
 * @author owlzhangfq@gmail.com
 */
public class SandboxSafeAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;

    public SandboxSafeAgentStateStore(AgentStateStore delegate) {
        this.delegate = delegate;
    }

    private String sanitize(String sessionId) {
        return sessionId == null ? null : sessionId.replace('/', '_').replace('\\', '_');
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, State state) {
        delegate.save(userId, sanitize(sessionId), stateKey, state);
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, List<? extends State> states) {
        delegate.save(userId, sanitize(sessionId), stateKey, states);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String stateKey, Class<T> clazz) {
        return delegate.get(userId, sanitize(sessionId), stateKey, clazz);
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String stateKey, Class<T> clazz) {
        return delegate.getList(userId, sanitize(sessionId), stateKey, clazz);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sanitize(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sanitize(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String stateKey) {
        delegate.delete(userId, sanitize(sessionId), stateKey);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
