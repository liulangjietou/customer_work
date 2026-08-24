package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentMemoryVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySnapshot;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryStore;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySyncService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 智能体长期记忆运维：查看/清空。
 *
 * <p>读写对象是权威存储 {@link AgentMemoryStore}（默认库表 ai_agent_memory，配置 disk-root 后为磁盘）；
 * 查看前先做一次 workspace → 权威存储的回写同步（拿到最近一轮对话刚 flush 的内容），清空时除权威存储外
 * 一并删除 workspace 下的工作副本（MEMORY.md 与 memory/ 原料目录），避免运行副本把旧记忆同步回来。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    /** 框架分层记忆的工作副本文件名（workspace 根下，路径约定来自 Harness WorkspaceManager）。 */
    /** 框架分层记忆的原料目录名（workspace 根下，存放按日/按会话沉淀的记忆文件）。 */
    private static final String MEMORY_DIR_NAME = "memory";
    private static final DateTimeFormatter UPDATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentService agentService;
    private final AdminAgentInstanceFactory instanceFactory;
    private final AgentMemoryStore memoryStore;
    private final AgentMemorySyncService memorySyncService;

    public AgentMemoryService(AgentService agentService, AdminAgentInstanceFactory instanceFactory,
                               AgentMemoryStore memoryStore, AgentMemorySyncService memorySyncService) {
        this.agentService = agentService;
        this.instanceFactory = instanceFactory;
        this.memoryStore = memoryStore;
        this.memorySyncService = memorySyncService;
    }

    /** 查看长期记忆：先同步 workspace 最新变更再读权威存储；从未沉淀过时返回 {@code exists=false}。 */
    public AgentMemoryVO getMemory(Long id) {
        AiAgent agent = agentService.requireAgent(id);
        String agentCode = agent.getAgentCode();
        AgentMemoryScope scope = AgentMemoryScope.current(agentCode);
        memorySyncService.persistIfChanged(scope.storageKey(), instanceFactory.resolveWorkspace(scope));
        try {
            Optional<AgentMemorySnapshot> snapshot = memoryStore.load(scope.storageKey());
            if (snapshot.isEmpty()) {
                return new AgentMemoryVO(false, "", null);
            }
            String updateTime = snapshot.get().updateTime() == null
                ? null : UPDATE_TIME_FORMATTER.format(snapshot.get().updateTime());
            return new AgentMemoryVO(true, snapshot.get().content(), updateTime);
        } catch (Exception e) {
            log.error("read agent memory failed, code={}, agentCode={}", "AGENT-MEMORY-READ-FAIL", agentCode, e);
            throw new BizException(ResultCode.AGENT_MEMORY_OPERATION_FAILED, "读取记忆失败: " + e.getMessage());
        }
    }

    /** 清空长期记忆：删除权威存储记录 + workspace 工作副本（MEMORY.md 与 memory/ 原料目录），幂等。 */
    public void clearMemory(Long id) {
        AiAgent agent = agentService.requireAgent(id);
        String agentCode = agent.getAgentCode();
        AgentMemoryScope scope = AgentMemoryScope.current(agentCode);
        Path workspace = instanceFactory.resolveWorkspace(scope);
        try {
            memoryStore.delete(scope.storageKey());
            boolean removed = Files.deleteIfExists(workspace.resolve(AgentFileNames.MEMORY_MD));
            deleteRecursively(workspace.resolve(MEMORY_DIR_NAME));
            log.info("agent memory cleared: agentCode={} workspaceCopyRemoved={}", agentCode, removed);
        } catch (Exception e) {
            log.error("clear agent memory failed, code={}, agentCode={}", "AGENT-MEMORY-CLEAR-FAIL", agentCode, e);
            throw new BizException(ResultCode.AGENT_MEMORY_OPERATION_FAILED, "清空记忆失败: " + e.getMessage());
        }
    }

    /** 递归删除目录（深度倒序保证先删文件再删目录），目录不存在时静默返回。 */
    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> ordered;
        try (Stream<Path> paths = Files.walk(dir)) {
            ordered = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : ordered) {
            Files.delete(path);
        }
    }
}
