package com.richard.fyoung.customerwork.runtime;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link SandboxSafeAgentStateStore} 单测：验证转发给底层 store 的 sessionId 已去除路径分隔符，
 * 规避 {@code MysqlAgentStateStore#validateSessionId} 拒绝含 "/" 的 sessionId 这一框架层面的
 * 兼容性问题（详见类 Javadoc）。
 * @author owlzhangfq@gmail.com
 */
class SandboxSafeAgentStateStoreTest {

    private final AgentStateStore delegate = mock(AgentStateStore.class);
    private final SandboxSafeAgentStateStore store = new SandboxSafeAgentStateStore(delegate);

    @Test
    void save_shouldSanitizeSlashInSessionId() {
        State state = mock(State.class);
        store.save("agentCode", "sandbox/agent/", "key", state);
        verify(delegate).save(eq("agentCode"), eq("sandbox_agent_"), eq("key"), eq(state));
    }

    @Test
    void save_list_shouldSanitizeSlashInSessionId() {
        List<State> states = List.of(mock(State.class));
        store.save("agentCode", "sandbox/session/abc", "key", (List<? extends State>) states);
        verify(delegate).save(eq("agentCode"), eq("sandbox_session_abc"), eq("key"), eq(states));
    }

    @Test
    void get_shouldSanitizeBackslashInSessionId() {
        store.get("agentCode", "sandbox\\user\\\\abc", "key", State.class);
        verify(delegate).get(eq("agentCode"), eq("sandbox_user__abc"), eq("key"), eq(State.class));
    }

    @Test
    void exists_shouldSanitizeSessionId() {
        store.exists("agentCode", "sandbox/global");
        verify(delegate).exists(eq("agentCode"), eq("sandbox_global"));
    }

    @Test
    void delete_shouldSanitizeSessionId() {
        store.delete("agentCode", "sandbox/agent/");
        verify(delegate).delete(eq("agentCode"), eq("sandbox_agent_"));
    }

    @Test
    void deleteWithStateKey_shouldSanitizeSessionId() {
        store.delete("agentCode", "sandbox/agent/", "key");
        verify(delegate).delete(eq("agentCode"), eq("sandbox_agent_"), eq("key"));
    }

    @Test
    void save_shouldPassThroughUnchanged_whenSessionIdHasNoSeparator() {
        State state = mock(State.class);
        store.save("agentCode", "plain-uuid-session-id", "key", state);
        verify(delegate).save(eq("agentCode"), eq("plain-uuid-session-id"), eq("key"), eq(state));
    }

    @Test
    void listSessionIds_shouldDelegateWithoutSanitizing() {
        store.listSessionIds("agentCode");
        verify(delegate).listSessionIds(eq("agentCode"));
    }

    @Test
    void close_shouldDelegate() {
        store.close();
        verify(delegate).close();
    }
}
