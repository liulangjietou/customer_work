package com.richard.fyoung.customeradmin.workspace.memory;

import java.util.Optional;

/**
 * 智能体跨会话长期记忆的权威存储 SPI。
 *
 * <p>Harness 分层记忆的工作副本永远是 {@code {workspace}/MEMORY.md}（框架 WorkspaceManager
 * 硬编码写磁盘，不可改）；本 SPI 负责它的持久化权威副本：构建实例时水合到 workspace、
 * 对话轮次结束后回写（见 {@link AgentMemorySyncService}）。</p>
 *
 * <p>实现选择由 {@link AgentMemoryStoreConfig} 按配置决定：默认 JDBC 落库
 * {@code ai_agent_memory}；配置 {@code admin.agent-memory.disk-root} 后改走磁盘。
 * 实现内部不做异常兜底（统一抛出运行时异常），由调用侧按场景处理：同步链路吞掉记日志、
 * 运维接口转业务异常。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AgentMemoryStore {

    /** 加载指定智能体的长期记忆；从未沉淀过（或已清空）时返回 empty。 */
    Optional<AgentMemorySnapshot> load(String agentCode);

    /** 保存（覆盖）指定智能体的长期记忆全文。 */
    default void save(String agentCode, String content) {
        long expectedVersion = load(agentCode).map(AgentMemorySnapshot::version).orElse(0L);
        if (!compareAndSet(agentCode, content, expectedVersion)) {
            throw new AgentMemoryVersionConflictException(agentCode, expectedVersion);
        }
    }

    /** 仅当权威版本仍等于 expectedVersion 时写入；新记录 expectedVersion=0。 */
    boolean compareAndSet(String agentCode, String content, long expectedVersion);

    /** 删除指定智能体的长期记忆（不存在视为成功，幂等）。 */
    void delete(String agentCode);
}
