package com.richard.fyoung.customeradmin.workspace.memory;

import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆同步：权威存储（{@link AgentMemoryStore}）与框架工作副本（{@code {workspace}/MEMORY.md}）
 * 之间的双向同步。
 *
 * <ul>
 *   <li><b>水合</b>（{@link #hydrate}）：构建智能体实例时把权威副本写到 workspace，保证换机/重启/
 *       清理 workspace 后框架仍能读到历史记忆；权威存储为空而 workspace 有存量文件时反向入库（存量迁移）。</li>
 *   <li><b>回写</b>（{@link #persistIfChanged}）：对话轮次结束后把 workspace 文件的变更保存回权威存储；
 *       内容未变化时跳过写入。</li>
 * </ul>
 *
 * <p>两个方法都不抛异常（同步失败不应打断对话主链路，也不应阻断实例构建），失败只记 error 日志；
 * 这是记忆同步链路的唯一异常兜底点，{@code AgentMemoryStore} 实现内部不再兜底。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AgentMemorySyncService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemorySyncService.class);

    private final AgentMemoryStore memoryStore;
    /** 每个工作副本水合时看到的权威版本；跨 Pod 的最终裁决仍由 Store CAS 完成。 */
    private final Map<String, Long> workspaceVersions = new ConcurrentHashMap<>();

    public AgentMemorySyncService(AgentMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /** 水合：权威存储 → workspace/MEMORY.md（构建实例时调用）；权威侧为空且 workspace 有存量文件时反向入库。 */
    public void hydrate(String agentCode, Path workspace) {
        try {
            Path memoryFile = workspace.resolve(AgentFileNames.MEMORY_MD);
            Optional<AgentMemorySnapshot> snapshot = memoryStore.load(agentCode);
            if (snapshot.isPresent()) {
                Files.writeString(memoryFile, snapshot.get().content(), StandardCharsets.UTF_8);
                workspaceVersions.put(workspaceKey(agentCode, workspace), snapshot.get().version());
                log.info("agent memory hydrated to workspace: agentCode={}", agentCode);
                return;
            }
            if (Files.exists(memoryFile)) {
                // 存量迁移：老版本记忆只落过 workspace 磁盘，第一次构建时收编进权威存储
                boolean created = memoryStore.compareAndSet(agentCode,
                    Files.readString(memoryFile, StandardCharsets.UTF_8), 0L);
                if (!created) {
                    throw new AgentMemoryVersionConflictException(agentCode, 0L);
                }
                workspaceVersions.put(workspaceKey(agentCode, workspace), 1L);
                log.info("legacy workspace memory imported into store: agentCode={}", agentCode);
            }
        } catch (Exception e) {
            log.error("hydrate agent memory failed, code={}, agentCode={}", "AGENT-MEMORY-HYDRATE-FAIL", agentCode, e);
        }
    }

    /** 回写：workspace/MEMORY.md → 权威存储（对话轮次结束后调用）；文件不存在或内容未变化时跳过。 */
    public void persistIfChanged(String agentCode, Path workspace) {
        try {
            Path memoryFile = workspace.resolve(AgentFileNames.MEMORY_MD);
            if (!Files.exists(memoryFile)) {
                return;
            }
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            Optional<AgentMemorySnapshot> current = memoryStore.load(agentCode);
            if (current.isPresent() && content.equals(current.get().content())) {
                workspaceVersions.put(workspaceKey(agentCode, workspace), current.get().version());
                return;
            }
            String workspaceKey = workspaceKey(agentCode, workspace);
            long expectedVersion = current.isEmpty() ? 0L
                : workspaceVersions.getOrDefault(workspaceKey, current.get().version());
            if (!memoryStore.compareAndSet(agentCode, content, expectedVersion)) {
                throw new AgentMemoryVersionConflictException(agentCode, expectedVersion);
            }
            workspaceVersions.put(workspaceKey, expectedVersion + 1L);
            log.info("agent memory persisted to store: agentCode={} bytes={}", agentCode, content.length());
        } catch (Exception e) {
            log.error("persist agent memory failed, code={}, agentCode={}", "AGENT-MEMORY-PERSIST-FAIL", agentCode, e);
        }
    }

    private String workspaceKey(String agentCode, Path workspace) {
        return agentCode + "\u001f" + workspace.toAbsolutePath().normalize();
    }
}
