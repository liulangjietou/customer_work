package com.richard.fyoung.customerwork.safety.security;

import io.micrometer.context.ThreadLocalAccessor;

/** 把可信 Agent 调用主体接入 Reactor 自动上下文传播。 */
public class AgentInvocationIdentityContextThreadLocalAccessor
        implements ThreadLocalAccessor<AgentInvocationIdentity> {

    public static final String KEY = "customer-work.agent-invocation-identity";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public AgentInvocationIdentity getValue() {
        return AgentInvocationIdentityContext.get();
    }

    @Override
    public void setValue(AgentInvocationIdentity value) {
        AgentInvocationIdentityContext.set(value);
    }

    @Override
    public void setValue() {
        AgentInvocationIdentityContext.clear();
    }
}
