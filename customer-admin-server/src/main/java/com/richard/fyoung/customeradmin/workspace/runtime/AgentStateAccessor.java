package com.richard.fyoung.customeradmin.workspace.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;

/**
 * 按运行时具体类型取 {@link AgentState}：{@code getAgentState(userId, sessionId)} 只直接声明在
 * {@link ReActAgent}/{@link HarnessAgent} 各自的类上（2.0.0-RC4 未收敛进共享的 {@code Agent} 接口，
 * 与 {@code ChatService} 里 {@code stream(...)} 分派同一个坑），故按类型分派——
 * {@link com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory#build} 只会
 * 产出这两种之一。抽成独立类是因为聊天流式对话（取当前会话状态）和历史会话查询（取任意历史会话状态）
 * 两处都要用，不重复写一遍。
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentStateAccessor {

    public AgentState resolve(Agent agent, String userId, String sessionId) {
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent.getAgentState(userId, sessionId);
        }
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.getDelegate().getAgentState(userId, sessionId);
        }
        throw new IllegalStateException("unsupported agent runtime type: " + agent.getClass());
    }
}
