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
 * {@link AgentMemoryService} 单测：查看走权威存储（查看前先回写同步）、清空同时删权威存储与
 * workspace 工作副本（含 memory/ 原料目录、幂等）。
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
        // 查看前必须先做一次 workspace → 权威存储的回写同步，保证能看到最近一轮 flush 的内容
        verify(memorySyncService).persistIfChanged(AGENT_CODE, workspace);
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
    void clearMemory_shouldBeIdempotent_whenNothingExists() {
        service.clearMemory(AGENT_ID);

        verify(memoryStore).delete(AGENT_CODE);
        assertFalse(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(workspace.resolve("memory")));
    }
}
