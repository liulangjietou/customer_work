package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.richard.fyoung.customeradmin.workspace.runtime.AgentWorkspaceManager;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentMemoryVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySnapshot;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryStore;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySyncService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * {@link AgentMemoryService} 单测：查看走权威存储（查看前先回写同步）、清空同时删权威存储、
 * 框架实际读写的 namespace 工作副本与 workspace 根下的遗留副本（含 memory/ 原料目录、幂等）。
 * @author owlzhangfq@gmail.com
 */
class AgentMemoryServiceTest {

    private static final Long AGENT_ID = 1L;
    private static final String AGENT_CODE = "memo-agent";

    @TempDir
    Path workspace;

    private AgentMemoryStore memoryStore;
    private AgentMemorySyncService memorySyncService;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        AgentService agentService = mock(AgentService.class);
        AiAgent agent = new AiAgent();
        agent.setId(AGENT_ID);
        agent.setAgentCode(AGENT_CODE);
        when(agentService.requireAgent(AGENT_ID)).thenReturn(agent);

        AdminAgentInstanceFactory instanceFactory = mock(AdminAgentInstanceFactory.class);
        // 框架按调用方 namespace 放工作副本（不在 workspace 根下），位置由实例工厂解析
        when(instanceFactory.memoryFile(any(AiAgent.class), any(AgentMemoryScope.class)))
            .thenReturn(namespacedMemoryFile());
        when(instanceFactory.memoryDir(any(AiAgent.class), any(AgentMemoryScope.class)))
            .thenReturn(namespacedMemoryDir());
        AgentWorkspaceManager workspaceManager = mock(AgentWorkspaceManager.class);
        when(workspaceManager.resolveWorkspace(any(AgentMemoryScope.class))).thenReturn(workspace);

        memoryStore = mock(AgentMemoryStore.class);
        memorySyncService = mock(AgentMemorySyncService.class);
        service = new AgentMemoryService(agentService, instanceFactory, memoryStore, memorySyncService, workspaceManager);
    }

    @Test
    void getMemory_shouldReturnNotExists_whenStoreEmpty() {
        when(memoryStore.load(AGENT_CODE)).thenReturn(Optional.empty());

        AgentMemoryVO vo = service.getMemory(AGENT_ID);

        assertFalse(vo.exists());
        assertEquals("", vo.content());
        assertNull(vo.updateTime());
        // 查看前必须先做一次工作副本 → 权威存储的回写同步，且同步的是框架实际读写的那一份
        verify(memorySyncService).persistIfChanged(AGENT_CODE, namespacedMemoryFile());
    }

    @Test
    void getMemory_shouldReturnSnapshot_whenStoreHasContent() {
        String content = "# MEMORY\n- 用户偏好 Java\n";
        when(memoryStore.load(AGENT_CODE))
            .thenReturn(Optional.of(new AgentMemorySnapshot(content, LocalDateTime.of(2026, 7, 22, 10, 30))));

        AgentMemoryVO vo = service.getMemory(AGENT_ID);

        assertTrue(vo.exists());
        assertEquals(content, vo.content());
        assertNotNull(vo.updateTime());
        assertEquals("2026-07-22 10:30:00", vo.updateTime());
    }

    @Test
    void clearMemory_shouldDeleteStoreAndWorkspaceCopy() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "profile", StandardCharsets.UTF_8);
        Path memoryDir = workspace.resolve("memory");
        Files.createDirectories(memoryDir.resolve("2026-07"));
        Files.writeString(memoryDir.resolve("2026-07-22.md"), "daily", StandardCharsets.UTF_8);
        Files.writeString(memoryDir.resolve("2026-07").resolve("nested.md"), "nested", StandardCharsets.UTF_8);

        service.clearMemory(AGENT_ID);

        verify(memoryStore).delete(AGENT_CODE);
        assertFalse(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(memoryDir));
    }

    @Test
    void clearMemory_shouldDeleteNamespacedWorkingCopy_thatFrameworkActuallyReads() throws Exception {
        Files.createDirectories(namespacedMemoryDir().resolve("2026-11"));
        Files.writeString(namespacedMemoryFile(), "profile", StandardCharsets.UTF_8);
        Files.writeString(namespacedMemoryDir().resolve("2026-11").resolve("daily.md"), "daily", StandardCharsets.UTF_8);

        service.clearMemory(AGENT_ID);

        assertFalse(Files.exists(namespacedMemoryFile()), "只删 workspace 根下的副本，运行中的实例照样读到旧记忆");
        assertFalse(Files.exists(namespacedMemoryDir()));
    }

    @Test
    void clearMemory_shouldBeIdempotent_whenNothingExists() {
        service.clearMemory(AGENT_ID);

        verify(memoryStore).delete(AGENT_CODE);
        assertFalse(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(workspace.resolve("memory")));
    }

    private Path namespacedMemoryFile() {
        return workspace.resolve("caller-ns").resolve("MEMORY.md");
    }

    private Path namespacedMemoryDir() {
        return workspace.resolve("caller-ns").resolve("memory");
    }
}
