package com.richard.fyoung.customeradmin.workspace.memory;

import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** {@link AgentMemoryScope} 可信主体隔离契约测试。 */
class AgentMemoryScopeTest {

    @AfterEach
    void clearIdentity() {
        AgentInvocationIdentityContext.clear();
    }

    @Test
    void shouldIgnoreClientSessionAndRemainStableForSameSubject() {
        AgentInvocationIdentity base = identity("user-a");
        AgentInvocationIdentityContext.set(base.forInvocation("admin", "session-1", "agent-a"));
        AgentMemoryScope first = AgentMemoryScope.current("agent-a");
        AgentInvocationIdentityContext.set(base.forInvocation("admin", "session-2", "agent-a"));
        AgentMemoryScope second = AgentMemoryScope.current("agent-a");

        assertEquals(first.storageKey(), second.storageKey());
    }

    @Test
    void shouldIsolateSubjectsWithoutLeakingRawSubjectId() {
        AgentInvocationIdentityContext.set(identity("external-user-a"));
        AgentMemoryScope first = AgentMemoryScope.current("agent-a");
        AgentInvocationIdentityContext.set(identity("external-user-b"));
        AgentMemoryScope second = AgentMemoryScope.current("agent-a");

        assertNotEquals(first.storageKey(), second.storageKey());
        assertFalse(first.storageKey().contains("external-user-a"));
        assertFalse(second.storageKey().contains("external-user-b"));
    }

    private AgentInvocationIdentity identity(String subjectId) {
        return new AgentInvocationIdentity("tenant-a", QuotaSubjectType.USER, subjectId, true);
    }
}
