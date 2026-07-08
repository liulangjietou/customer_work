package com.richard.fyoung.customeradmin.workspace.runtime;

import io.agentscope.core.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体实例缓存：{@code agentCode -> Agent}，惰性重建、不预热。
 *
 * <p>{@code ai_agent}/{@code ai_agent_mcp}/{@code ai_agent_skill}/{@code ai_model_config} 任一写操作
 * 成功后，对应 Service 会调用 {@link #evict}/{@link #evictAll} 清掉受影响的 agentCode；下次调用
 * {@link #getOrBuild} 时 {@link ConcurrentHashMap#computeIfAbsent} 保证同一 agentCode 并发只重建一次。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentInstanceCache {

    private final ConcurrentHashMap<String, Agent> cache = new ConcurrentHashMap<>();
    private final AdminAgentInstanceFactory factory;

    public AgentInstanceCache(AdminAgentInstanceFactory factory) {
        this.factory = factory;
    }

    public Agent getOrBuild(String agentCode) {
        return cache.computeIfAbsent(agentCode, factory::build);
    }

    public void evict(String agentCode) {
        cache.remove(agentCode);
    }

    public void evictAll(Collection<String> agentCodes) {
        agentCodes.forEach(cache::remove);
    }
}
