package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Agent 及其 Toolkit 的统一释放入口。 */
public final class AgentResourceCloser {

    private static final Logger log = LoggerFactory.getLogger(AgentResourceCloser.class);
    private static final String CODE_AGENT_CLOSE_FAIL = "AGENT-RESOURCE-CLOSE-FAIL";

    private AgentResourceCloser() {
    }

    /**
     * 释放 Agent 自身资源及其 Toolkit；异常只记录，不阻断缓存淘汰或容器关闭。
     *
     * <p>AgentScope 2.0 的 {@link ReActAgent#close()} 是空实现，{@link HarnessAgent#close()}
     * 只释放 Harness 自有资源，二者都不会关闭 Toolkit，因此必须显式补上后半段。</p>
     */
    public static void closeQuietly(Agent agent, String owner) {
        if (agent == null) {
            return;
        }
        Toolkit toolkit = toolkitOf(agent);
        try {
            if (agent instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.error("Agent close failed, code={}, owner={}, agentName={}",
                CODE_AGENT_CLOSE_FAIL, owner, agent.getName(), e);
        } finally {
            closeToolkit(toolkit, owner, agent.getName());
        }
    }

    private static Toolkit toolkitOf(Agent agent) {
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.getToolkit();
        }
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent.getToolkit();
        }
        return null;
    }

    private static void closeToolkit(Toolkit toolkit, String owner, String agentName) {
        if (!(toolkit instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.error("Agent toolkit close failed, code={}, owner={}, agentName={}",
                CODE_AGENT_CLOSE_FAIL, owner, agentName, e);
        }
    }
}
