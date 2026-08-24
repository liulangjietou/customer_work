package com.richard.fyoung.customerwork.safety.security;

import java.util.function.Supplier;

/** 鉴权入口建立的 Agent 调用主体上下文；与配额主体分离，关闭配额不会丢失授权事实。 */
public final class AgentInvocationIdentityContext {

    private static final ThreadLocal<AgentInvocationIdentity> CURRENT = new ThreadLocal<>();

    private AgentInvocationIdentityContext() {
    }

    public static void set(AgentInvocationIdentity identity) {
        if (identity == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(identity);
        }
    }

    public static AgentInvocationIdentity get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callWith(AgentInvocationIdentity identity, Supplier<T> action) {
        AgentInvocationIdentity previous = CURRENT.get();
        set(identity);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }
}
