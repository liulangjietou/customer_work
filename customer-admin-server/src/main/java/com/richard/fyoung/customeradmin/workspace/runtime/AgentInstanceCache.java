package com.richard.fyoung.customeradmin.workspace.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.core.agent.AgentResourceCloser;
import io.agentscope.core.agent.Agent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体实例缓存：{@code tenantId + agentCode -> Agent}，惰性重建、不预热。
 *
 * <p>{@code ai_agent}/{@code ai_agent_mcp}/{@code ai_agent_skill}/{@code ai_model_config} 任一写操作
 * 成功后，对应 Service 会调用 {@link #evict}/{@link #evictAll} 清掉受影响的 agentCode；下次调用
 * {@link #getOrBuild} 时 {@link ConcurrentHashMap#computeIfAbsent} 保证同一 agentCode 并发只重建一次。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentInstanceCache {

    private final ConcurrentHashMap<String, CachedAgent> cache = new ConcurrentHashMap<>();
    private final AdminAgentInstanceFactory factory;
    /** 可空仅用于旧单测构造；生产必须注入 Mapper 才具备跨 Pod 修订校验。 */
    private final AiAgentMapper agentMapper;

    @Autowired
    public AgentInstanceCache(AdminAgentInstanceFactory factory, AiAgentMapper agentMapper) {
        this.factory = factory;
        this.agentMapper = agentMapper;
    }

    /** 兼容既有离线单测：没有数据库时保持原来的进程内缓存语义。 */
    public AgentInstanceCache(AdminAgentInstanceFactory factory) {
        this(factory, null);
    }

    public Agent getOrBuild(String agentCode) {
        AgentMemoryScope scope = AgentMemoryScope.current(agentCode);
        String key = scope.storageKey();
        if (agentMapper == null) {
            return cache.computeIfAbsent(key,
                ignored -> new CachedAgent(factory.build(agentCode, scope), 0L)).agent();
        }
        AiAgent current = requireEnabled(agentCode);
        long revision = current.getRuntimeRevision() == null ? 0L : current.getRuntimeRevision();
        CachedAgent cached = cache.get(key);
        if (cached != null && cached.revision() == revision) {
            return cached.agent();
        }
        return cache.compute(key, (ignored, existing) -> {
            if (existing != null && existing.revision() == revision) {
                return existing;
            }
            CachedAgent replacement = new CachedAgent(factory.build(agentCode, scope), revision);
            close(existing, "revision-replaced:" + key);
            return replacement;
        }).agent();
    }

    public void evict(String agentCode) {
        String base = WorkspaceRuntimeScope.agent(agentCode);
        cache.forEach((key, value) -> {
            if ((key.equals(base) || key.startsWith(base + "::subject::"))
                && cache.remove(key, value)) {
                close(value, "evicted:" + key);
            }
        });
    }

    public void evictAll(Collection<String> agentCodes) {
        agentCodes.forEach(this::evict);
    }

    /**
     * 可靠失效：先在数据库原子推进共享修订号，再清本 Pod；其它 Pod 下一次读取会因修订不一致重建。
     */
    public void invalidate(String agentCode) {
        if (agentMapper != null) {
            agentMapper.bumpRuntimeRevision(agentCode);
        }
        evict(agentCode);
    }

    public void invalidateAll(Collection<String> agentCodes) {
        agentCodes.forEach(this::invalidate);
    }

    @PreDestroy
    void destroy() {
        cache.forEach((key, value) -> {
            if (cache.remove(key, value)) {
                close(value, "cache-destroy:" + key);
            }
        });
    }

    private void close(CachedAgent cached, String owner) {
        if (cached != null) {
            AgentResourceCloser.closeQuietly(cached.agent(), owner);
        }
    }

    private AiAgent requireEnabled(String agentCode) {
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getAgentCode, agentCode));
        if (agent == null) {
            evict(agentCode);
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + agentCode);
        }
        if (agent.getStatus() == null || agent.getStatus() != StatusFlags.ENABLED) {
            evict(agentCode);
            throw new BizException(ResultCode.AGENT_DISABLED, "智能体未启用: " + agentCode);
        }
        return agent;
    }

    private record CachedAgent(Agent agent, long revision) {
    }
}
